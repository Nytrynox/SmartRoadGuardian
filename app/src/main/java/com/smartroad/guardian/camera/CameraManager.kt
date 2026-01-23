package com.smartroad.guardian.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera Manager using CameraX
 * 
 * As per specification:
 * - Captures frames at 640x640 target
 * - Provides bitmap for ML inference
 * - Supports snapshot capture for evidence
 */
class CameraManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "CameraManager"
        private const val TARGET_RESOLUTION_WIDTH = 640
        private const val TARGET_RESOLUTION_HEIGHT = 480
    }
    
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isProcessing = AtomicBoolean(false)
    
    @Volatile
    private var isRunning = false
    
    /**
     * Initialize camera with preview and frame analysis
     */
    fun initialize(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onFrame: (Bitmap, Int) -> Unit
    ): Boolean {
        return try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            
            cameraProviderFuture.addListener({
                cameraProvider = cameraProviderFuture.get()
                
                // Preview
                val preview = Preview.Builder()
                    .setTargetResolution(Size(TARGET_RESOLUTION_WIDTH, TARGET_RESOLUTION_HEIGHT))
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                
                // Image capture for evidence
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetResolution(Size(1280, 720))
                    .build()
                
                // Image analysis for ML
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetResolution(Size(TARGET_RESOLUTION_WIDTH, TARGET_RESOLUTION_HEIGHT))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also {
                        it.setAnalyzer(executor) { imageProxy ->
                            if (!isProcessing.getAndSet(true)) {
                                processImage(imageProxy, onFrame)
                            } else {
                                imageProxy.close()
                            }
                        }
                    }
                
                // Camera selector - back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                try {
                    cameraProvider?.unbindAll()
                    camera = cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture,
                        imageAnalyzer
                    )
                    isRunning = true
                    Log.i(TAG, "Camera initialized successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Camera binding failed: ${e.message}", e)
                }
                
            }, ContextCompat.getMainExecutor(context))
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Camera init failed: ${e.message}", e)
            false
        }
    }
    
    /**
     * Process camera frame and convert to Bitmap
     */
    private fun processImage(imageProxy: ImageProxy, onFrame: (Bitmap, Int) -> Unit) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                onFrame(bitmap, imageProxy.imageInfo.rotationDegrees)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}")
        } finally {
            imageProxy.close()
            isProcessing.set(false)
        }
    }
    
    /**
     * Convert ImageProxy to Bitmap (YUV to RGB)
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        
        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )
        
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 80, out)
        val imageBytes = out.toByteArray()
        
        var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        
        // Rotate if needed
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotation.toFloat())
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        
        return bitmap
    }
    
    /**
     * Capture high-resolution snapshot for evidence
     */
    fun captureSnapshot(callback: (Bitmap?) -> Unit) {
        val capture = imageCapture ?: return callback(null)
        
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()
                    callback(bitmap)
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed: ${exception.message}")
                    callback(null)
                }
            }
        )
    }
    
    /**
     * Toggle flashlight/torch
     */
    fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }
    
    /**
     * Check if torch is available
     */
    fun hasTorch(): Boolean {
        return camera?.cameraInfo?.hasFlashUnit() == true
    }
    
    /**
     * Release camera resources
     */
    fun release() {
        isRunning = false
        cameraProvider?.unbindAll()
        executor.shutdown()
        Log.i(TAG, "Camera released")
    }
}
