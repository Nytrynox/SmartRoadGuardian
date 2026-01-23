package com.smartroad.guardian.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import com.smartroad.guardian.storage.ViolationDatabase
import com.smartroad.guardian.utils.PreferencesManager
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.mail.*
import javax.mail.internet.*

/**
 * Email Worker - Sends violation reports via SMTP
 * 
 * Features per PRD:
 * - Daily scheduled reports
 * - On-demand report sending
 * - Attachment support
 * - Test email capability
 */
class EmailWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "EmailWorker"
        private const val WORK_NAME = "email_report"
        private const val KEY_RECIPIENT = "recipient"
        private const val KEY_IS_TEST = "is_test"
        
        /**
         * Schedule daily email report at 8 PM
         */
        fun scheduleDailyReport(context: Context) {
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val delayHours = if (currentHour < 20) {
                20 - currentHour
            } else {
                24 - currentHour + 20
            }.toLong()
            
            val request = PeriodicWorkRequestBuilder<EmailWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayHours, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("daily_report")
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            
            Log.i(TAG, "Daily report scheduled in $delayHours hours")
        }
        
        /**
         * Send report now
         */
        fun scheduleReport(context: Context) {
            val request = OneTimeWorkRequestBuilder<EmailWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "Report scheduled")
        }
        
        /**
         * Send test email
         */
        fun sendTestEmail(context: Context, recipient: String) {
            val request = OneTimeWorkRequestBuilder<EmailWorker>()
                .setInputData(
                    workDataOf(
                        KEY_RECIPIENT to recipient,
                        KEY_IS_TEST to true
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "Test email scheduled to $recipient")
        }
    }

    private val preferencesManager = PreferencesManager(applicationContext)
    private val database = ViolationDatabase.getInstance(applicationContext)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override suspend fun doWork(): Result {
        return try {
            val isTest = inputData.getBoolean(KEY_IS_TEST, false)
            val recipient = inputData.getString(KEY_RECIPIENT) 
                ?: preferencesManager.emailRecipient.first()
            
            if (recipient.isEmpty()) {
                Log.e(TAG, "No recipient configured")
                return Result.failure()
            }
            
            if (isTest) {
                sendTestEmail(recipient)
            } else {
                sendViolationReport(recipient)
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Email failed: ${e.message}", e)
            Result.retry()
        }
    }
    
    private suspend fun sendTestEmail(recipient: String) {
        val subject = "SmartRoad Guardian - Test Email"
        val body = """
            This is a test email from SmartRoad Guardian.
            
            If you received this, your email configuration is working correctly.
            
            ---
            Sent: ${dateTimeFormat.format(Date())}
        """.trimIndent()
        
        sendEmail(recipient, subject, body, emptyList())
        Log.i(TAG, "Test email sent to $recipient")
    }
    
    private suspend fun sendViolationReport(recipient: String) {
        val dao = database.violationDao()
        val unsynced = dao.getUnsynced()
        
        if (unsynced.isEmpty()) {
            Log.i(TAG, "No violations to report")
            return
        }
        
        val today = dateFormat.format(Date())
        val subject = "SmartRoad Guardian - Violation Report ($today)"
        
        // Build report
        val builder = StringBuilder()
        builder.appendLine("SmartRoad Guardian - Daily Violation Report")
        builder.appendLine("=" .repeat(50))
        builder.appendLine()
        builder.appendLine("Date: $today")
        builder.appendLine("Total Violations: ${unsynced.size}")
        builder.appendLine()
        
        // Group by type
        val byType = unsynced.groupBy { it.type }
        builder.appendLine("Summary by Type:")
        builder.appendLine("-".repeat(30))
        for ((type, list) in byType) {
            builder.appendLine("  $type: ${list.size}")
        }
        builder.appendLine()
        
        // Violation details
        builder.appendLine("Detailed Violations:")
        builder.appendLine("-".repeat(30))
        for (violation in unsynced.take(50)) { // Limit to 50 for email size
            builder.appendLine()
            builder.appendLine("ID: ${violation.id}")
            builder.appendLine("Type: ${violation.type}")
            builder.appendLine("Confidence: ${String.format("%.1f%%", violation.confidence * 100)}")
            builder.appendLine("Time: ${dateTimeFormat.format(Date(violation.timestamp))}")
            if (violation.latitude != 0.0 || violation.longitude != 0.0) {
                builder.appendLine("Location: ${violation.latitude}, ${violation.longitude}")
            }
        }
        
        if (unsynced.size > 50) {
            builder.appendLine()
            builder.appendLine("... and ${unsynced.size - 50} more violations")
        }
        
        builder.appendLine()
        builder.appendLine("-".repeat(50))
        builder.appendLine("Generated by SmartRoad Guardian")
        
        // Get some images as attachments (limit to 5)
        val attachments = unsynced.take(5).mapNotNull { 
            File(it.imagePath).takeIf { f -> f.exists() } 
        }
        
        sendEmail(recipient, subject, builder.toString(), attachments)
        
        // Mark as synced
        val ids = unsynced.map { it.id }
        dao.markAsSynced(ids)
        
        Log.i(TAG, "Report sent: ${unsynced.size} violations to $recipient")
    }
    
    private suspend fun sendEmail(
        recipient: String,
        subject: String,
        body: String,
        attachments: List<File>
    ) {
        val smtpServer = preferencesManager.smtpServer.first().ifEmpty { "smtp.gmail.com" }
        val smtpPort = preferencesManager.smtpPort.first()
        val username = preferencesManager.smtpUsername.first()
        val password = preferencesManager.smtpPassword.first()
        
        if (username.isEmpty() || password.isEmpty()) {
            Log.e(TAG, "SMTP credentials not configured")
            throw IllegalStateException("SMTP credentials not configured")
        }
        
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", smtpServer)
            put("mail.smtp.port", smtpPort.toString())
            put("mail.smtp.ssl.trust", smtpServer)
        }
        
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(username, password)
        })
        
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(username))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient))
            setSubject(subject)
            sentDate = Date()
        }
        
        if (attachments.isEmpty()) {
            message.setText(body)
        } else {
            val multipart = MimeMultipart()
            
            // Text part
            val textPart = MimeBodyPart()
            textPart.setText(body)
            multipart.addBodyPart(textPart)
            
            // Attachment parts
            for (file in attachments) {
                val attachPart = MimeBodyPart()
                attachPart.attachFile(file)
                multipart.addBodyPart(attachPart)
            }
            
            message.setContent(multipart)
        }
        
        Transport.send(message)
    }
}
