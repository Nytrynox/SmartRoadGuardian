package com.smartroad.guardian.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.smartroad.guardian.R
import com.smartroad.guardian.camera.CameraManager
import com.smartroad.guardian.databinding.ActivityMainBinding
import com.smartroad.guardian.logic.ViolationEngine
import com.smartroad.guardian.ml.Detection
import com.smartroad.guardian.ml.TrackerEngine
import com.smartroad.guardian.ml.YoloInterpreter
import com.smartroad.guardian.storage.ViolationRepository
import com.smartroad.guardian.utils.GpsUtils
import com.smartroad.guardian.utils.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Main Activity - Live Detection Screen
 * 
 * Full implementation per PRD:
 * - Real-time camera preview with detection overlay
 * - All violation types with visual + audio feedback
 * - Session management with statistics
 * - Low battery handling
 * - Manual capture option
 * - Performance monitoring
 * 
 * NO DEMO MODE. NO FAKE DATA. REAL AI ONLY.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val LOW_BATTERY_WARNING = 20
        private const val CRITICAL_BATTERY_STOP = 10
        private const val FRAME_SKIP_BATTERY_SAVER = 2
        
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    private lateinit var binding: ActivityMainBinding
    
    // Core components
    private lateinit var cameraManager: CameraManager
    private lateinit var detector: YoloInterpreter
    private lateinit var tracker: TrackerEngine
    private lateinit var violationEngine: ViolationEngine
    private lateinit var repository: ViolationRepository
    private lateinit var gpsUtils: GpsUtils
    private lateinit var preferencesManager: PreferencesManager
    
    // Audio feedback
    private var ringtone: android.media.Ringtone? = null
    private var audioEnabled = true
    
    // State
    private val isDetecting = AtomicBoolean(false)
    private val frameCounter = AtomicInteger(0)
    private var sessionStartTime: Long = 0
    private var sessionViolationCount: Int = 0
    private var sessionDetectionCount: Int = 0
    private var batterySaverMode = false
    
    // Current frame for evidence
    @Volatile
    private var currentFrame: Bitmap? = null
    private var currentFrameWidth = 0
    private var currentFrameHeight = 0
    
    // Handler for UI updates
    private val handler = Handler(Looper.getMainLooper())
    private var sessionTimeRunnable: Runnable? = null
    private var batteryCheckRunnable: Runnable? = null
    
    // Settings
    private var confidenceThreshold = 0.5f
    private var helmetDetectionEnabled = true
    private var tripleRidingEnabled = true
    private var plateDetectionEnabled = true
    private var vibrationEnabled = true

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            onPermissionsGranted()
        } else {
            showPermissionError()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on during detection
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Fullscreen immersive
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        initComponents()
        initSoundPool()
        setupClickListeners()
        loadSettings()
        observeData()
        
        if (hasPermissions()) {
            onPermissionsGranted()
        } else {
            requestPermissions()
        }
    }

    private fun initComponents() {
        cameraManager = CameraManager(this)
        detector = YoloInterpreter(this)
        tracker = TrackerEngine()
        violationEngine = ViolationEngine()
        repository = ViolationRepository(this)
        gpsUtils = GpsUtils(this)
        preferencesManager = PreferencesManager(this)
    }
    
    private fun initSoundPool() {
        // Load system notification sound for violation alerts
        try {
            val notification = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_NOTIFICATION
            )
            ringtone = android.media.RingtoneManager.getRingtone(this, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load notification sound")
        }
    }
    
    private fun loadSettings() {
        lifecycleScope.launch {
            confidenceThreshold = preferencesManager.confidenceThreshold.first()
            helmetDetectionEnabled = preferencesManager.helmetDetectionEnabled.first()
            tripleRidingEnabled = preferencesManager.tripleRidingEnabled.first()
            plateDetectionEnabled = preferencesManager.plateDetectionEnabled.first()
            vibrationEnabled = preferencesManager.vibrationEnabled.first()
            audioEnabled = preferencesManager.audioEnabled.first()
        }
    }

    private fun setupClickListeners() {
        // Start/Stop button
        binding.btnStartStop.setOnClickListener {
            if (isDetecting.get()) {
                stopDetection()
            } else {
                startDetection()
            }
        }
        
        // Manual capture button
        binding.btnManualCapture.setOnClickListener {
            if (isDetecting.get()) {
                captureManualSnapshot()
            } else {
                Toast.makeText(this, "Start detection first", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Dashboard
        binding.btnDashboard.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
        }
        
        // Settings
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        // Permission grant
        binding.btnGrantPermission.setOnClickListener {
            requestPermissions()
        }
        
        // Torch toggle
        binding.btnTorch?.setOnClickListener {
            toggleTorch()
        }
    }
    
    private fun toggleTorch() {
        if (cameraManager.hasTorch()) {
            val newState = !(binding.btnTorch?.isSelected ?: false)
            cameraManager.setTorch(newState)
            binding.btnTorch?.isSelected = newState
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            repository.getTodayCount().collect { count ->
                binding.todayViolationsCount.text = count.toString()
            }
        }
        
        lifecycleScope.launch {
            repository.getUnsyncedCount().collect { count ->
                binding.pendingSyncCount.text = count.toString()
            }
        }
    }

    private fun hasPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun showPermissionError() {
        binding.permissionOverlay.visibility = View.VISIBLE
    }

    private fun onPermissionsGranted() {
        binding.permissionOverlay.visibility = View.GONE
        initializeCamera()
        loadModel()
    }

    private fun initializeCamera() {
        lifecycleScope.launch {
            try {
                val success = cameraManager.initialize(
                    lifecycleOwner = this@MainActivity,
                    previewView = binding.previewView,
                    onFrame = { bitmap, _ ->
                        processFrame(bitmap)
                    }
                )
                
                if (success) {
                    Log.i(TAG, "Camera initialized")
                    updateStatusText(getString(R.string.idle))
                } else {
                    Log.e(TAG, "Camera init failed")
                    Toast.makeText(this@MainActivity, R.string.error_camera_init, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera error: ${e.message}", e)
            }
        }
    }

    private fun loadModel() {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.loadingText.text = "Loading AI model..."
        
        lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    detector.initialize()
                }
                
                binding.loadingOverlay.visibility = View.GONE
                
                if (success) {
                    val accel = when {
                        detector.isUsingGpu() -> "GPU"
                        detector.isUsingNnapi() -> "NPU"
                        else -> "CPU"
                    }
                    val modelInfo = "${detector.getModelName()} ($accel)"
                    Log.i(TAG, "Model: $modelInfo, Classes: ${detector.getNumClasses()}")
                    
                    binding.modelInfo?.text = modelInfo
                    Toast.makeText(this@MainActivity, "AI Ready: $accel", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e(TAG, "Model load failed")
                    showModelLoadError()
                }
            } catch (e: Exception) {
                binding.loadingOverlay.visibility = View.GONE
                Log.e(TAG, "Model error: ${e.message}", e)
                showModelLoadError()
            }
        }
    }
    
    private fun showModelLoadError() {
        AlertDialog.Builder(this)
            .setTitle("Model Load Failed")
            .setMessage("Could not load AI model. Detection will not work.")
            .setPositiveButton("Retry") { _, _ -> loadModel() }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .show()
    }

    private fun startDetection() {
        // Check battery level
        val batteryLevel = getBatteryLevel()
        if (batteryLevel < CRITICAL_BATTERY_STOP) {
            Toast.makeText(this, "Battery too low to start", Toast.LENGTH_LONG).show()
            return
        }
        
        isDetecting.set(true)
        sessionStartTime = System.currentTimeMillis()
        sessionViolationCount = 0
        sessionDetectionCount = 0
        frameCounter.set(0)
        tracker.reset()
        violationEngine.reset()
        
        // UI updates
        binding.btnStartStop.text = getString(R.string.stop_detection)
        binding.btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.error))
        binding.sessionViolations?.text = "0"
        updateStatusText(getString(R.string.detecting))
        
        // Enable manual capture
        binding.btnManualCapture.visibility = View.VISIBLE
        binding.btnManualCapture.isEnabled = true
        
        // Start GPS
        if (gpsUtils.hasLocationPermission()) {
            gpsUtils.startTracking { location ->
                runOnUiThread {
                    binding.gpsStatusDot.setBackgroundResource(
                        if (location != null) R.drawable.circle_indicator 
                        else R.drawable.circle_pending
                    )
                }
            }
        }
        
        startSessionTimer()
        startBatteryMonitor()
        
        Log.i(TAG, "Detection STARTED")
    }

    private fun stopDetection() {
        isDetecting.set(false)
        
        // UI updates
        binding.btnStartStop.text = getString(R.string.start_detection)
        binding.btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        updateStatusText(getString(R.string.idle))
        binding.btnManualCapture.visibility = View.GONE
        
        // Stop services
        gpsUtils.stopLocationUpdates()
        stopSessionTimer()
        stopBatteryMonitor()
        binding.overlayView.clear()
        
        // Show session summary
        showSessionSummary()
        
        Log.i(TAG, "Detection STOPPED - Violations: $sessionViolationCount, Detections: $sessionDetectionCount")
    }
    
    private fun showSessionSummary() {
        val duration = (System.currentTimeMillis() - sessionStartTime) / 1000
        val minutes = duration / 60
        val seconds = duration % 60
        
        val avgFps = if (duration > 0) frameCounter.get() / duration.toFloat() else 0f
        
        AlertDialog.Builder(this)
            .setTitle("Session Complete")
            .setMessage("""
                Duration: ${minutes}m ${seconds}s
                Violations: $sessionViolationCount
                Detections: $sessionDetectionCount
                Avg FPS: %.1f
            """.trimIndent().format(avgFps))
            .setPositiveButton("OK", null)
            .setNeutralButton("View Dashboard") { _, _ ->
                startActivity(Intent(this, DashboardActivity::class.java))
            }
            .show()
    }

    private fun processFrame(bitmap: Bitmap) {
        if (!isDetecting.get()) return
        
        // Frame skipping in battery saver mode
        val frameNum = frameCounter.incrementAndGet()
        if (batterySaverMode && frameNum % FRAME_SKIP_BATTERY_SAVER != 0) {
            return
        }
        
        currentFrame = bitmap
        currentFrameWidth = bitmap.width
        currentFrameHeight = bitmap.height
        
        // Log every 30 frames
        if (frameNum % 30 == 0) {
            Log.d(TAG, "Processing frame $frameNum: ${bitmap.width}x${bitmap.height}")
        }
        
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                // Run detection
                val rawDetections = detector.detect(bitmap, confidenceThreshold)
                
                if (rawDetections.isNotEmpty() || frameNum % 30 == 0) {
                    Log.d(TAG, "Frame $frameNum: ${rawDetections.size} raw detections")
                }
                
                // Track objects
                val trackedDetections = tracker.update(rawDetections)
                
                // Filter by enabled violation types and check violations
                val violations = checkViolations(trackedDetections)
                
                // Save violations
                for (violation in violations) {
                    saveViolation(violation, bitmap)
                }
                
                sessionDetectionCount += trackedDetections.size
                
                // Update UI
                withContext(Dispatchers.Main) {
                    updateOverlay(trackedDetections, violations)
                    updateStats()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame error: ${e.message}", e)
            }
        }
    }
    
    private fun checkViolations(detections: List<Detection>): List<ViolationEngine.Violation> {
        val allViolations = violationEngine.analyze(detections)
        
        // Filter by enabled types
        return allViolations.filter { violation ->
            when (violation.type) {
                ViolationEngine.ViolationType.NO_HELMET -> helmetDetectionEnabled
                ViolationEngine.ViolationType.TRIPLE_RIDING -> tripleRidingEnabled
                ViolationEngine.ViolationType.NO_PLATE -> plateDetectionEnabled
            }
        }
    }

    private suspend fun saveViolation(violation: ViolationEngine.Violation, frame: Bitmap) {
        try {
            val location = gpsUtils.lastLocation
            withContext(Dispatchers.IO) {
                repository.saveViolation(violation, frame, location)
            }
            
            sessionViolationCount++
            
            withContext(Dispatchers.Main) {
                binding.sessionViolations?.text = sessionViolationCount.toString()
                alertViolation(violation)
            }
            
            Log.i(TAG, "VIOLATION: ${violation.type.displayName}")
        } catch (e: Exception) {
            Log.e(TAG, "Save error: ${e.message}")
        }
    }
    
    private fun alertViolation(violation: ViolationEngine.Violation) {
        // Audio feedback
        if (audioEnabled) {
            try {
                ringtone?.play()
            } catch (e: Exception) {
                Log.w(TAG, "Could not play notification sound")
            }
        }
        
        // Vibration feedback
        if (vibrationEnabled) {
            vibrateOnViolation()
        }
        
        // Visual flash on overlay
        binding.overlayView.flashViolation(violation.type)
    }
    
    private fun captureManualSnapshot() {
        currentFrame?.let { frame ->
            lifecycleScope.launch {
                try {
                    val location = gpsUtils.lastLocation
                    withContext(Dispatchers.IO) {
                        repository.saveManualCapture(frame, location)
                    }
                    Toast.makeText(this@MainActivity, "Manual capture saved", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateOverlay(detections: List<Detection>, violations: List<ViolationEngine.Violation>) {
        binding.overlayView.setDetections(detections, currentFrameWidth, currentFrameHeight)
        binding.overlayView.setViolations(violations)
    }

    private fun updateStats() {
        val fps = detector.getFps()
        val latency = detector.getTotalTime()
        
        binding.overlayView.updateStats(
            fps = fps,
            latency = latency,
            violationCount = sessionViolationCount,
            isDetecting = isDetecting.get()
        )
    }
    
    private fun updateStatusText(status: String) {
        binding.statusText.text = status
    }

    private fun startSessionTimer() {
        sessionTimeRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - sessionStartTime
                val minutes = (elapsed / 60000).toInt()
                val seconds = ((elapsed % 60000) / 1000).toInt()
                binding.sessionTime.text = String.format("%02d:%02d", minutes, seconds)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(sessionTimeRunnable!!)
    }

    private fun stopSessionTimer() {
        sessionTimeRunnable?.let { handler.removeCallbacks(it) }
        sessionTimeRunnable = null
    }
    
    private fun startBatteryMonitor() {
        batteryCheckRunnable = object : Runnable {
            override fun run() {
                checkBatteryLevel()
                handler.postDelayed(this, 30000) // Check every 30 seconds
            }
        }
        handler.postDelayed(batteryCheckRunnable!!, 30000)
    }
    
    private fun stopBatteryMonitor() {
        batteryCheckRunnable?.let { handler.removeCallbacks(it) }
        batteryCheckRunnable = null
    }
    
    private fun checkBatteryLevel() {
        val level = getBatteryLevel()
        
        if (level < CRITICAL_BATTERY_STOP && isDetecting.get()) {
            stopDetection()
            AlertDialog.Builder(this)
                .setTitle("Battery Critical")
                .setMessage("Detection stopped due to low battery ($level%)")
                .setPositiveButton("OK", null)
                .show()
        } else if (level < LOW_BATTERY_WARNING && !batterySaverMode) {
            batterySaverMode = true
            Toast.makeText(this, "Low battery - enabling power saver mode", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun vibrateOnViolation() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VibratorManager::class.java)
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(200)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed")
        }
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        observeData()
    }
    
    override fun onPause() {
        super.onPause()
        if (isDetecting.get()) {
            // Keep running in background - don't stop detection
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDetection()
        cameraManager.release()
        detector.close()
        ringtone?.stop()
        stopSessionTimer()
        stopBatteryMonitor()
    }
}
