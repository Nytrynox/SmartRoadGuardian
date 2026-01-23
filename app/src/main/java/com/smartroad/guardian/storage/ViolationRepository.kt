package com.smartroad.guardian.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.location.Location
import android.util.Log
import com.smartroad.guardian.logic.ViolationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Repository for managing violation data
 * 
 * Handles:
 * - Saving violations with snapshots
 * - Database operations
 * - Image management
 * - Manual captures
 * - Export functionality
 */
class ViolationRepository(
    private val context: Context,
    private val database: ViolationDatabase = ViolationDatabase.getInstance(context)
) {
    companion object {
        private const val TAG = "ViolationRepository"
        private const val VIOLATIONS_DIR = "violations"
        private const val THUMBNAILS_DIR = "thumbnails"
        private const val MANUAL_DIR = "manual_captures"
        private const val JPEG_QUALITY = 90
        private const val THUMBNAIL_SIZE = 200
        private const val MAX_VIOLATIONS = 500
        private const val MAX_STORAGE_MB = 2000
    }

    private val dao = database.violationDao()
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /**
     * Save a violation with its snapshot
     */
    suspend fun saveViolation(
        violation: ViolationEngine.Violation,
        frameBitmap: Bitmap,
        location: Location?
    ): Long = withContext(Dispatchers.IO) {
        try {
            // Check storage limits
            if (!checkStorageLimits()) {
                Log.w(TAG, "Storage limit reached, skipping save")
                return@withContext -1
            }
            
            // Draw annotations
            val annotatedBitmap = drawAnnotations(frameBitmap, violation)
            
            // Save images
            val imagePath = saveImage(annotatedBitmap, violation.type.name, violation.timestamp)
            val thumbnailPath = saveThumbnail(annotatedBitmap, violation.type.name, violation.timestamp)
            
            // Get bounding box
            val mainBbox = violation.relatedDetections.firstOrNull()?.boundingBox
            
            // Create entity
            val entity = ViolationEntity(
                type = violation.type.name,
                confidence = violation.confidence,
                timestamp = violation.timestamp,
                latitude = location?.latitude ?: 0.0,
                longitude = location?.longitude ?: 0.0,
                imagePath = imagePath,
                thumbnailPath = thumbnailPath,
                trackId = violation.relatedDetections.firstOrNull()?.trackId ?: -1,
                bboxLeft = mainBbox?.left ?: 0f,
                bboxTop = mainBbox?.top ?: 0f,
                bboxRight = mainBbox?.right ?: 0f,
                bboxBottom = mainBbox?.bottom ?: 0f
            )
            
            val id = dao.insert(entity)
            Log.i(TAG, "Saved ${violation.type.name} ID: $id")
            id
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}", e)
            -1
        }
    }
    
    /**
     * Save manual capture (user-triggered)
     */
    suspend fun saveManualCapture(
        bitmap: Bitmap,
        location: Location?
    ): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, MANUAL_DIR)
        if (!dir.exists()) dir.mkdirs()
        
        val timestamp = System.currentTimeMillis()
        val filename = "manual_${dateFormat.format(Date(timestamp))}.jpg"
        val file = File(dir, filename)
        
        // Add timestamp overlay
        val annotated = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 32f
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }
        
        val timestampText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(timestamp))
        canvas.drawText(timestampText, 20f, annotated.height - 60f, paint)
        
        if (location != null) {
            val locText = String.format("%.6f, %.6f", location.latitude, location.longitude)
            canvas.drawText(locText, 20f, annotated.height - 20f, paint)
        }
        
        FileOutputStream(file).use { out ->
            annotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        
        Log.i(TAG, "Manual capture: ${file.absolutePath}")
        file.absolutePath
    }

    private fun drawAnnotations(bitmap: Bitmap, violation: ViolationEngine.Violation): Bitmap {
        val annotated = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        
        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = getColorForViolation(violation.type)
        }
        
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 32f
            isFakeBoldText = true
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }
        
        val labelBgPaint = Paint().apply {
            style = Paint.Style.FILL
            color = boxPaint.color
        }
        
        for (detection in violation.relatedDetections) {
            val rect = RectF(
                detection.boundingBox.left,
                detection.boundingBox.top,
                detection.boundingBox.right,
                detection.boundingBox.bottom
            )
            canvas.drawRect(rect, boxPaint)
            
            val label = "${violation.type.displayName} ${(violation.confidence * 100).toInt()}%"
            val textWidth = textPaint.measureText(label)
            val labelRect = RectF(
                rect.left,
                rect.top - 40,
                rect.left + textWidth + 16,
                rect.top
            )
            canvas.drawRect(labelRect, labelBgPaint)
            canvas.drawText(label, rect.left + 8, rect.top - 10, textPaint)
        }
        
        // Timestamp
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(violation.timestamp))
        canvas.drawText(timestamp, 20f, annotated.height - 20f, textPaint)
        
        return annotated
    }

    private fun getColorForViolation(type: ViolationEngine.ViolationType): Int {
        return when (type) {
            ViolationEngine.ViolationType.NO_HELMET -> Color.parseColor("#FF5722")
            ViolationEngine.ViolationType.TRIPLE_RIDING -> Color.parseColor("#E91E63")
            ViolationEngine.ViolationType.NO_PLATE -> Color.parseColor("#9C27B0")
        }
    }

    private suspend fun saveImage(bitmap: Bitmap, typeName: String, timestamp: Long): String {
        val dir = File(context.filesDir, VIOLATIONS_DIR)
        if (!dir.exists()) dir.mkdirs()
        
        val filename = "${typeName}_${dateFormat.format(Date(timestamp))}.jpg"
        val file = File(dir, filename)
        
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        
        return file.absolutePath
    }

    private suspend fun saveThumbnail(bitmap: Bitmap, typeName: String, timestamp: Long): String {
        val dir = File(context.filesDir, THUMBNAILS_DIR)
        if (!dir.exists()) dir.mkdirs()
        
        val filename = "${typeName}_${dateFormat.format(Date(timestamp))}_thumb.jpg"
        val file = File(dir, filename)
        
        val aspectRatio = bitmap.height.toFloat() / bitmap.width
        val thumbnail = Bitmap.createScaledBitmap(
            bitmap, 
            THUMBNAIL_SIZE, 
            (THUMBNAIL_SIZE * aspectRatio).toInt(), 
            true
        )
        
        FileOutputStream(file).use { out ->
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 70, out)
        }
        
        return file.absolutePath
    }
    
    private suspend fun checkStorageLimits(): Boolean {
        val count = dao.getTotalCountSync()
        if (count >= MAX_VIOLATIONS) {
            Log.w(TAG, "Max violations ($MAX_VIOLATIONS) reached")
            return false
        }
        
        val storageMb = getStorageUsed() / (1024 * 1024)
        if (storageMb >= MAX_STORAGE_MB) {
            Log.w(TAG, "Max storage (${MAX_STORAGE_MB}MB) reached")
            return false
        }
        
        return true
    }

    // Query methods
    fun getAllViolations() = dao.getAllViolations()
    fun getTodayViolations() = dao.getTodayViolations(getStartOfDay())
    fun getViolationsByType(type: String) = dao.getByType(type)
    suspend fun getUnsyncedViolations() = dao.getUnsynced()
    suspend fun markAsSynced(ids: List<Long>) = dao.markAsSynced(ids)
    fun getViolationStats() = dao.getViolationStats()
    fun getTotalCount() = dao.getTotalCount()
    fun getTodayCount() = dao.getTodayCount(getStartOfDay())
    fun getUnsyncedCount() = dao.getUnsyncedCount()

    suspend fun deleteViolation(id: Long) {
        val violation = dao.getById(id)
        violation?.let {
            File(it.imagePath).delete()
            it.thumbnailPath?.let { path -> File(path).delete() }
            dao.deleteById(id)
        }
    }

    suspend fun deleteAll() {
        File(context.filesDir, VIOLATIONS_DIR).deleteRecursively()
        File(context.filesDir, THUMBNAILS_DIR).deleteRecursively()
        File(context.filesDir, MANUAL_DIR).deleteRecursively()
        dao.deleteAll()
    }

    suspend fun exportToCsv(): File = withContext(Dispatchers.IO) {
        val violations = dao.getAllViolationsList()
        val exportDir = File(context.getExternalFilesDir(null), "exports")
        if (!exportDir.exists()) exportDir.mkdirs()
        
        val filename = "smartroad_violations_${dateFormat.format(Date())}.csv"
        val file = File(exportDir, filename)
        
        file.bufferedWriter().use { writer ->
            writer.write("ID,Type,Confidence,Timestamp,DateTime,Latitude,Longitude,ImagePath,IsSynced\n")
            
            for (v in violations) {
                val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date(v.timestamp))
                writer.write("${v.id},${v.type},${v.confidence},${v.timestamp},$dateTime," +
                    "${v.latitude},${v.longitude},${v.imagePath},${v.isSynced}\n")
            }
        }
        
        Log.i(TAG, "Exported ${violations.size} to ${file.absolutePath}")
        file
    }

    suspend fun getStorageUsed(): Long = withContext(Dispatchers.IO) {
        val dirs = listOf(VIOLATIONS_DIR, THUMBNAILS_DIR, MANUAL_DIR)
        dirs.sumOf { dirName ->
            File(context.filesDir, dirName).walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        }
    }

    private fun getStartOfDay(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
