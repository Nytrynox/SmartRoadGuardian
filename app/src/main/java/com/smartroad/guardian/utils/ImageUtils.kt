package com.smartroad.guardian.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Image processing utilities
 */
object ImageUtils {
    
    private const val TAG = "ImageUtils"

    /**
     * Resize bitmap to target size while maintaining aspect ratio
     */
    fun resize(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val ratioBitmap = width.toFloat() / height.toFloat()
        val ratioMax = maxWidth.toFloat() / maxHeight.toFloat()
        
        var finalWidth = maxWidth
        var finalHeight = maxHeight
        
        if (ratioMax > ratioBitmap) {
            finalWidth = (maxHeight.toFloat() * ratioBitmap).toInt()
        } else {
            finalHeight = (maxWidth.toFloat() / ratioBitmap).toInt()
        }
        
        return Bitmap.createScaledBitmap(bitmap, finalWidth, finalHeight, true)
    }

    /**
     * Rotate bitmap by degrees
     */
    fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply {
            postRotate(degrees)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Apply Gaussian blur to a region (for face blurring)
     */
    fun blurRegion(bitmap: Bitmap, region: RectF, blurRadius: Int = 25): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        // Extract region
        val left = region.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = region.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, bitmap.height)
        
        val width = right - left
        val height = bottom - top
        
        if (width <= 0 || height <= 0) return result
        
        // Simple box blur by downscaling and upscaling
        val regionBitmap = Bitmap.createBitmap(result, left, top, width, height)
        
        // Downscale
        val smallWidth = (width / blurRadius).coerceAtLeast(1)
        val smallHeight = (height / blurRadius).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(regionBitmap, smallWidth, smallHeight, true)
        
        // Upscale back
        val blurred = Bitmap.createScaledBitmap(small, width, height, true)
        
        // Draw back onto result
        val canvas = Canvas(result)
        canvas.drawBitmap(blurred, left.toFloat(), top.toFloat(), null)
        
        return result
    }

    /**
     * Create mosaic/pixelate effect on region
     */
    fun pixelateRegion(bitmap: Bitmap, region: RectF, pixelSize: Int = 10): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        val left = region.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = region.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, bitmap.height)
        
        val width = right - left
        val height = bottom - top
        
        if (width <= 0 || height <= 0) return result
        
        val regionBitmap = Bitmap.createBitmap(result, left, top, width, height)
        
        // Downscale to pixelate
        val smallWidth = (width / pixelSize).coerceAtLeast(1)
        val smallHeight = (height / pixelSize).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(regionBitmap, smallWidth, smallHeight, false)
        
        // Upscale without filtering
        val pixelated = Bitmap.createScaledBitmap(small, width, height, false)
        
        val canvas = Canvas(result)
        canvas.drawBitmap(pixelated, left.toFloat(), top.toFloat(), null)
        
        return result
    }

    /**
     * Compress bitmap to JPEG bytes
     */
    fun toJpegBytes(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return outputStream.toByteArray()
    }

    /**
     * Save bitmap to file
     */
    suspend fun saveToFile(
        bitmap: Bitmap,
        file: File,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 85
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(format, quality, out)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bitmap: ${e.message}")
            false
        }
    }

    /**
     * Load bitmap from file with optional size constraints
     */
    suspend fun loadFromFile(
        file: File,
        maxWidth: Int = 0,
        maxHeight: Int = 0
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (maxWidth > 0 && maxHeight > 0) {
                // Get dimensions first
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
                
                // Calculate sample size
                options.inSampleSize = calculateInSampleSize(
                    options.outWidth, options.outHeight, maxWidth, maxHeight
                )
                options.inJustDecodeBounds = false
                
                BitmapFactory.decodeFile(file.absolutePath, options)
            } else {
                BitmapFactory.decodeFile(file.absolutePath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap: ${e.message}")
            null
        }
    }

    /**
     * Calculate sample size for efficient loading
     */
    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            while ((halfHeight / inSampleSize) >= reqHeight &&
                   (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }

    /**
     * Create a cropped version of the bitmap
     */
    fun crop(bitmap: Bitmap, rect: RectF): Bitmap? {
        val left = rect.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = rect.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = rect.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, bitmap.height)
        
        val width = right - left
        val height = bottom - top
        
        return if (width > 0 && height > 0) {
            Bitmap.createBitmap(bitmap, left, top, width, height)
        } else null
    }
}
