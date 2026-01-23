package com.smartroad.guardian.ui

import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.smartroad.guardian.R
import com.smartroad.guardian.databinding.ActivitySettingsBinding
import com.smartroad.guardian.ml.YoloInterpreter
import com.smartroad.guardian.storage.ViolationRepository
import com.smartroad.guardian.utils.PreferencesManager
import com.smartroad.guardian.workers.EmailWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings Activity - Full Configuration per PRD
 * 
 * Features:
 * - Confidence threshold (0.3-0.9)
 * - Per-violation-type toggles
 * - Email configuration
 * - Storage management
 * - Model info display
 * - Audio/vibration settings
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var repository: ViolationRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferencesManager = PreferencesManager(this)
        repository = ViolationRepository(this)
        
        setupToolbar()
        loadSettings()
        setupListeners()
        loadModelInfo()
        loadStorageInfo()
    }
    
    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }
    
    private fun loadSettings() {
        lifecycleScope.launch {
            // Detection settings
            val confidence = preferencesManager.confidenceThreshold.first()
            binding.confidenceSlider.progress = ((confidence - 0.3f) / 0.6f * 100).toInt()
            binding.confidenceValue.text = String.format("%.0f%%", confidence * 100)
            
            // Violation types
            binding.switchHelmet.isChecked = preferencesManager.helmetDetectionEnabled.first()
            binding.switchTripleRiding.isChecked = preferencesManager.tripleRidingEnabled.first()
            binding.switchNoPlate.isChecked = preferencesManager.plateDetectionEnabled.first()
            binding.switchWrongWay.isChecked = preferencesManager.wrongWayEnabled.first()
            
            // Feedback settings
            binding.switchVibration.isChecked = preferencesManager.vibrationEnabled.first()
            binding.switchAudio.isChecked = preferencesManager.audioEnabled.first()
            
            // Email settings
            binding.emailRecipient.setText(preferencesManager.emailRecipient.first())
            binding.smtpServer.setText(preferencesManager.smtpServer.first())
            binding.smtpPort.setText(preferencesManager.smtpPort.first().toString())
            binding.smtpUsername.setText(preferencesManager.smtpUsername.first())
        }
    }
    
    private fun setupListeners() {
        // Confidence slider
        binding.confidenceSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 0.3f + (progress / 100f) * 0.6f // 0.3 to 0.9
                binding.confidenceValue.text = String.format("%.0f%%", value * 100)
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    val value = 0.3f + (it.progress / 100f) * 0.6f
                    saveConfidence(value)
                }
            }
        })
        
        // Violation toggles
        binding.switchHelmet.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                preferencesManager.setHelmetDetectionEnabled(isChecked)
            }
        }
        
        binding.switchTripleRiding.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                preferencesManager.setTripleRidingEnabled(isChecked)
            }
        }
        
        binding.switchNoPlate.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                preferencesManager.setPlateDetectionEnabled(isChecked)
            }
        }
        
        binding.switchWrongWay.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                preferencesManager.setWrongWayEnabled(isChecked)
            }
        }
        
        // Feedback toggles
        binding.switchVibration.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                preferencesManager.setVibrationEnabled(isChecked)
            }
        }
        
        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                preferencesManager.setAudioEnabled(isChecked)
            }
        }
        
        // Save email settings
        binding.btnSaveEmail.setOnClickListener {
            saveEmailSettings()
        }
        
        // Test email
        binding.btnTestEmail.setOnClickListener {
            testEmail()
        }
        
        // Clear data
        binding.btnClearData.setOnClickListener {
            confirmClearData()
        }
        
        // Export data
        binding.btnExportData.setOnClickListener {
            exportData()
        }
    }
    
    private fun saveConfidence(value: Float) {
        lifecycleScope.launch {
            preferencesManager.setConfidenceThreshold(value)
            Toast.makeText(this@SettingsActivity, "Confidence: ${(value * 100).toInt()}%", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun saveEmailSettings() {
        val recipient = binding.emailRecipient.text.toString().trim()
        val server = binding.smtpServer.text.toString().trim()
        val port = binding.smtpPort.text.toString().toIntOrNull() ?: 587
        val username = binding.smtpUsername.text.toString().trim()
        val password = binding.smtpPassword.text.toString()
        
        if (recipient.isEmpty()) {
            Toast.makeText(this, "Please enter recipient email", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            preferencesManager.setEmailRecipient(recipient)
            preferencesManager.setSmtpServer(server)
            preferencesManager.setSmtpPort(port)
            preferencesManager.setSmtpUsername(username)
            if (password.isNotEmpty()) {
                preferencesManager.setSmtpPassword(password)
            }
            
            Toast.makeText(this@SettingsActivity, "Email settings saved", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun testEmail() {
        val recipient = binding.emailRecipient.text.toString().trim()
        if (recipient.isEmpty()) {
            Toast.makeText(this, "Please enter recipient email first", Toast.LENGTH_SHORT).show()
            return
        }
        
        EmailWorker.sendTestEmail(this, recipient)
        Toast.makeText(this, "Test email queued", Toast.LENGTH_SHORT).show()
    }
    
    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_clear_data)
            .setMessage("This will permanently delete all violation records and images. This cannot be undone.")
            .setPositiveButton(R.string.confirm_yes) { _, _ ->
                clearAllData()
            }
            .setNegativeButton(R.string.confirm_no, null)
            .show()
    }
    
    private fun clearAllData() {
        lifecycleScope.launch {
            try {
                repository.deleteAll()
                loadStorageInfo()
                Toast.makeText(this@SettingsActivity, "All data cleared", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Failed to clear data", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun exportData() {
        lifecycleScope.launch {
            try {
                val file = repository.exportToCsv()
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Export Complete")
                    .setMessage("Data exported to:\n${file.absolutePath}")
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun loadModelInfo() {
        lifecycleScope.launch {
            try {
                val detector = YoloInterpreter(this@SettingsActivity)
                if (detector.initialize()) {
                    val acceleration = when {
                        detector.isUsingGpu() -> "GPU (Adreno)"
                        detector.isUsingNnapi() -> "NPU (Hexagon)"
                        else -> "CPU"
                    }
                    
                    binding.modelName.text = detector.getModelName()
                    binding.modelClasses.text = "${detector.getNumClasses()} classes"
                    binding.modelAcceleration.text = acceleration
                    binding.modelType.text = if (detector.isYoloModel()) "YOLOv8" else "SSD MobileNet"
                    
                    detector.close()
                } else {
                    binding.modelName.text = "Not loaded"
                    binding.modelClasses.text = "-"
                    binding.modelAcceleration.text = "-"
                    binding.modelType.text = "-"
                }
            } catch (e: Exception) {
                binding.modelName.text = "Error"
            }
        }
    }
    
    private fun loadStorageInfo() {
        lifecycleScope.launch {
            try {
                val storageBytes = repository.getStorageUsed()
                val storageMB = storageBytes / (1024f * 1024f)
                binding.storageUsed.text = String.format("%.1f MB", storageMB)
                
                val count = repository.getTotalCount().first()
                binding.violationCount.text = "$count violations"
                
                // Storage warning
                if (storageMB > 1500) {
                    binding.storageWarning.visibility = android.view.View.VISIBLE
                    binding.storageWarning.text = "⚠ Storage usage high. Consider clearing old data."
                } else {
                    binding.storageWarning.visibility = android.view.View.GONE
                }
            } catch (e: Exception) {
                binding.storageUsed.text = "Unknown"
            }
        }
    }
}
