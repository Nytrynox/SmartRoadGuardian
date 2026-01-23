package com.smartroad.guardian.ml

import android.graphics.RectF

/**
 * Detection result from YOLOv8/SSD model
 * As per specification - no fake data
 */
data class Detection(
    val classId: Int,
    val className: String,
    val confidence: Float,
    val boundingBox: BoundingBox,
    var trackId: Int = -1
) {
    fun getBoundingBox(): RectF {
        return RectF(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom)
    }
    
    fun getCenter(): Pair<Float, Float> {
        return Pair(
            (boundingBox.left + boundingBox.right) / 2,
            (boundingBox.top + boundingBox.bottom) / 2
        )
    }
    
    fun getWidth(): Float = boundingBox.right - boundingBox.left
    fun getHeight(): Float = boundingBox.bottom - boundingBox.top
    fun getArea(): Float = getWidth() * getHeight()
}

/**
 * Bounding box coordinates
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun iou(other: BoundingBox): Float {
        val x1 = maxOf(left, other.left)
        val y1 = maxOf(top, other.top)
        val x2 = minOf(right, other.right)
        val y2 = minOf(bottom, other.bottom)
        
        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val area1 = (right - left) * (bottom - top)
        val area2 = (other.right - other.left) * (other.bottom - other.top)
        val union = area1 + area2 - intersection
        
        return if (union > 0) intersection / union else 0f
    }
    
    fun contains(x: Float, y: Float): Boolean {
        return x >= left && x <= right && y >= top && y <= bottom
    }
    
    fun centerX(): Float = (left + right) / 2
    fun centerY(): Float = (top + bottom) / 2
}

/**
 * Class labels for detection
 * Following specification: person, helmet, motorcycle, car, license_plate, traffic_light
 */
object DetectionClasses {
    // Custom trained model classes (when available)
    const val PERSON = 0
    const val HELMET = 1
    const val MOTORCYCLE = 2
    const val CAR = 3
    const val LICENSE_PLATE = 4
    const val TRAFFIC_LIGHT = 5
    
    val CUSTOM_LABELS = arrayOf(
        "person", "helmet", "motorcycle", "car", "license_plate", "traffic_light"
    )
    
    // COCO class mapping (for pre-trained model)
    val COCO_LABELS = arrayOf(
        "???", "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
        "boat", "traffic light", "fire hydrant", "???", "stop sign", "parking meter", "bench",
        "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe",
        "???", "backpack", "umbrella", "???", "???", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard",
        "surfboard", "tennis racket", "bottle", "???", "wine glass", "cup", "fork", "knife",
        "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog",
        "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed", "???", "dining table",
        "???", "???", "toilet", "???", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
        "microwave", "oven", "toaster", "sink", "refrigerator", "???", "book", "clock", "vase",
        "scissors", "teddy bear", "hair drier", "toothbrush"
    )
    
    // Relevant classes for traffic violation detection
    val TRAFFIC_RELEVANT_CLASSES = setOf(
        "person", "bicycle", "car", "motorcycle", "bus", "truck", "traffic light"
    )
    
    fun isRelevant(className: String): Boolean {
        return TRAFFIC_RELEVANT_CLASSES.contains(className.lowercase())
    }
}
