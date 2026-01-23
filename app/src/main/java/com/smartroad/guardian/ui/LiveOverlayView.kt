package com.smartroad.guardian.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.smartroad.guardian.logic.ViolationEngine
import com.smartroad.guardian.ml.Detection

/**
 * Enhanced Overlay View for Real-Time Detection
 * 
 * Features per PRD:
 * - Color-coded boxes (Green=compliant, Red=violation)
 * - FPS/latency stats
 * - Violation alerts with flash effect
 * - Smooth animations
 */
class LiveOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Detection data
    private var detections: List<Detection> = emptyList()
    private var violations: List<ViolationEngine.Violation> = emptyList()
    private var frameWidth: Int = 1
    private var frameHeight: Int = 1
    
    // Stats
    private var fps: Float = 0f
    private var latency: Long = 0
    private var violationCount: Int = 0
    private var isDetecting: Boolean = false
    
    // Flash effect
    private var flashAlpha: Float = 0f
    private var flashColor: Int = Color.RED
    private var flashAnimator: ValueAnimator? = null

    // Class colors (Green for compliant objects, specific colors for types)
    private val classColors = mapOf(
        "person" to Color.parseColor("#4CAF50"),       // Green
        "helmet" to Color.parseColor("#2196F3"),       // Blue
        "motorcycle" to Color.parseColor("#FF9800"),   // Orange
        "car" to Color.parseColor("#9C27B0"),          // Purple
        "truck" to Color.parseColor("#795548"),        // Brown
        "bus" to Color.parseColor("#607D8B"),          // Gray
        "bicycle" to Color.parseColor("#00BCD4"),      // Cyan
        "traffic light" to Color.parseColor("#FFEB3B"), // Yellow
        "license_plate" to Color.parseColor("#E91E63") // Pink
    )
    
    // Violation colors (Red shades)
    private val violationColors = mapOf(
        ViolationEngine.ViolationType.NO_HELMET to Color.parseColor("#F44336"),
        ViolationEngine.ViolationType.TRIPLE_RIDING to Color.parseColor("#E91E63"),
        ViolationEngine.ViolationType.NO_PLATE to Color.parseColor("#FF5722")
    )
    
    // Violation track IDs (to show red boxes)
    private val violationTrackIds = mutableSetOf<Int>()

    // Paints
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    
    private val statsPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(3f, 2f, 2f, Color.BLACK)
    }
    
    private val violationPaint = Paint().apply {
        textSize = 32f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    
    private val flashPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    
    private val cornerRadius = 8f
    private val labelPadding = 6f
    
    // Corner line length for futuristic look
    private val cornerLength = 20f

    fun setDetections(detections: List<Detection>, frameWidth: Int, frameHeight: Int) {
        this.detections = detections
        this.frameWidth = if (frameWidth > 0) frameWidth else 1
        this.frameHeight = if (frameHeight > 0) frameHeight else 1
        
        if (detections.isNotEmpty()) {
            android.util.Log.d("LiveOverlay", "setDetections: ${detections.size} items, frame=${frameWidth}x${frameHeight}")
        }
        
        postInvalidate()
    }
    
    fun setViolations(violations: List<ViolationEngine.Violation>) {
        this.violations = violations
        
        // Track violation-related detections
        violationTrackIds.clear()
        for (violation in violations) {
            for (det in violation.relatedDetections) {
                if (det.trackId >= 0) {
                    violationTrackIds.add(det.trackId)
                }
            }
        }
        
        postInvalidate()
    }
    
    fun updateStats(fps: Float, latency: Long, violationCount: Int, isDetecting: Boolean) {
        this.fps = fps
        this.latency = latency
        this.violationCount = violationCount
        this.isDetecting = isDetecting
        postInvalidate()
    }
    
    fun flashViolation(type: ViolationEngine.ViolationType) {
        flashColor = violationColors[type] ?: Color.RED
        
        flashAnimator?.cancel()
        flashAnimator = ValueAnimator.ofFloat(0.4f, 0f).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                flashAlpha = animator.animatedValue as Float
                postInvalidate()
            }
            start()
        }
    }
    
    fun clear() {
        detections = emptyList()
        violations = emptyList()
        violationTrackIds.clear()
        fps = 0f
        latency = 0
        flashAlpha = 0f
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw flash effect
        if (flashAlpha > 0) {
            flashPaint.color = Color.argb((flashAlpha * 255).toInt(), 
                Color.red(flashColor), Color.green(flashColor), Color.blue(flashColor))
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
        }
        
        val scaleX = width.toFloat() / frameWidth
        val scaleY = height.toFloat() / frameHeight
        
        // Draw detections
        for (detection in detections) {
            drawDetection(canvas, detection, scaleX, scaleY)
        }
        
        // Draw violation alerts
        drawViolationAlerts(canvas)
        
        // Draw stats
        drawStats(canvas)
    }
    
    private fun drawDetection(canvas: Canvas, detection: Detection, scaleX: Float, scaleY: Float) {
        val box = detection.boundingBox
        val left = box.left * scaleX
        val top = box.top * scaleY
        val right = box.right * scaleX
        val bottom = box.bottom * scaleY
        
        // Determine if this detection is involved in a violation
        val isViolation = detection.trackId in violationTrackIds
        
        val color = if (isViolation) {
            Color.parseColor("#FF1744") // Red for violations
        } else {
            classColors[detection.className.lowercase()] ?: Color.parseColor("#4CAF50") // Green default
        }
        
        boxPaint.color = color
        
        // Draw futuristic corner brackets instead of full box
        drawCornerBrackets(canvas, left, top, right, bottom, boxPaint)
        
        // Draw center crosshair for violations
        if (isViolation) {
            drawCrosshair(canvas, (left + right) / 2, (top + bottom) / 2, color)
        }
        
        // Draw label
        val label = "${detection.className} ${(detection.confidence * 100).toInt()}%"
        val textWidth = textPaint.measureText(label)
        val textHeight = textPaint.textSize
        
        // Label background
        fillPaint.color = Color.argb(180, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawRoundRect(
            left, top - textHeight - labelPadding * 2,
            left + textWidth + labelPadding * 2, top,
            cornerRadius, cornerRadius, fillPaint
        )
        
        // Label text
        canvas.drawText(label, left + labelPadding, top - labelPadding - 2, textPaint)
        
        // Track ID badge
        if (detection.trackId >= 0) {
            val trackLabel = "#${detection.trackId}"
            val trackWidth = textPaint.measureText(trackLabel) + labelPadding * 2
            fillPaint.color = Color.argb(200, 0, 0, 0)
            canvas.drawRoundRect(
                right - trackWidth, bottom,
                right, bottom + textHeight + labelPadding,
                cornerRadius, cornerRadius, fillPaint
            )
            canvas.drawText(trackLabel, right - trackWidth + labelPadding, bottom + textHeight, textPaint)
        }
    }
    
    private fun drawCornerBrackets(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        // Log coordinates occasionally to debug
        if (Math.random() < 0.01) {
             android.util.Log.d("LiveOverlay", "Drawing box: [$left, $top, $right, $bottom]")
        }

        // Draw full box for better visibility as requested
        canvas.drawRect(left, top, right, bottom, paint)
        
        // Add corner accents for "futuristic" look
        val len = minOf(cornerLength, (right - left) / 3, (bottom - top) / 3)
        val cornerPaint = Paint(paint).apply { 
            strokeWidth = paint.strokeWidth * 2 
            style = Paint.Style.STROKE
        }
        
        // Top-left
        canvas.drawLine(left, top, left + len, top, cornerPaint)
        canvas.drawLine(left, top, left, top + len, cornerPaint)
        
        // Top-right
        canvas.drawLine(right - len, top, right, top, cornerPaint)
        canvas.drawLine(right, top, right, top + len, cornerPaint)
        
        // Bottom-left
        canvas.drawLine(left, bottom - len, left, bottom, cornerPaint)
        canvas.drawLine(left, bottom, left + len, bottom, cornerPaint)
        
        // Bottom-right
        canvas.drawLine(right, bottom - len, right, bottom, cornerPaint)
        canvas.drawLine(right - len, bottom, right, bottom, cornerPaint)
    }
    
    private fun drawCrosshair(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val size = 15f
        val paint = Paint().apply {
            this.color = color
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(cx - size, cy, cx + size, cy, paint)
        canvas.drawLine(cx, cy - size, cx, cy + size, paint)
    }
    
    private fun drawViolationAlerts(canvas: Canvas) {
        if (violations.isEmpty()) return
        
        var yOffset = 120f
        for (violation in violations) {
            val color = violationColors[violation.type] ?: Color.RED
            violationPaint.color = color
            
            // Background
            val alert = "⚠ ${violation.type.displayName.uppercase()}"
            val alertWidth = violationPaint.measureText(alert) + 24f
            fillPaint.color = Color.argb(200, 0, 0, 0)
            canvas.drawRoundRect(
                20f, yOffset - 30f,
                20f + alertWidth, yOffset + 8f,
                12f, 12f, fillPaint
            )
            
            // Text
            canvas.drawText(alert, 32f, yOffset, violationPaint)
            yOffset += 50f
        }
    }
    
    private fun drawStats(canvas: Canvas) {
        if (!isDetecting) return
        
        // Stats panel background
        val panelWidth = 280f
        val panelHeight = 90f
        fillPaint.color = Color.argb(180, 0, 0, 0)
        canvas.drawRoundRect(
            20f, 20f,
            20f + panelWidth, 20f + panelHeight,
            12f, 12f, fillPaint
        )
        
        // FPS
        val fpsColor = when {
            fps >= 20 -> Color.parseColor("#4CAF50") // Green
            fps >= 15 -> Color.parseColor("#FFEB3B") // Yellow
            else -> Color.parseColor("#F44336")       // Red
        }
        statsPaint.color = fpsColor
        canvas.drawText(String.format("FPS: %.1f", fps), 35f, 50f, statsPaint)
        
        // Latency
        statsPaint.color = Color.WHITE
        canvas.drawText("${latency}ms", 160f, 50f, statsPaint)
        
        // Violations
        statsPaint.color = if (violationCount > 0) Color.parseColor("#FF5722") else Color.WHITE
        canvas.drawText("Violations: $violationCount", 35f, 85f, statsPaint)
        
        // Detection count
        statsPaint.color = Color.parseColor("#03A9F4")
        canvas.drawText("${detections.size} obj", 200f, 85f, statsPaint)
    }
}
