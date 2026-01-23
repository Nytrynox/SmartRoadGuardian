package com.smartroad.guardian.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 TensorFlow Lite Interpreter
 * 
 * Supports both:
 * - YOLOv8 models (640x640 input, [1,84,8400] output)
 * - SSD MobileNet models (300x300 input, multi-output)
 * 
 * Optimized for Snapdragon 8 Gen 3:
 * - GPU Delegate (Adreno GPU)
 * - NNAPI Delegate (Hexagon NPU)
 * 
 * NO DEMO MODE. NO FAKE DATA. REAL AI ONLY.
 */
class YoloInterpreter(
    private val context: Context,
    private val useGpu: Boolean = true,
    private val useNnapi: Boolean = true
) {
    companion object {
        private const val TAG = "YoloInterpreter"
        
        // Model files to try in order
        private val MODEL_FILES = listOf(
            "yolov8n_int8.tflite",  // Custom trained YOLOv8
            "yolov8n.tflite",       // YOLOv8 float
            "detect.tflite"          // SSD MobileNet fallback
        )
        
        // YOLOv8 configuration
        const val YOLO_INPUT_SIZE = 640
        const val YOLO_NUM_PREDICTIONS = 8400
        const val YOLO_NUM_FEATURES = 84  // 4 bbox + 80 classes (COCO) or custom
        
        // SSD configuration  
        const val SSD_INPUT_SIZE = 300
        const val SSD_MAX_DETECTIONS = 10
        
        // Detection thresholds
        const val DEFAULT_CONFIDENCE = 0.5f
        const val DEFAULT_IOU = 0.45f
    }
    
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnapiDelegate: NnApiDelegate? = null
    
    private var inputBuffer: ByteBuffer? = null
    private var isYoloModel = false
    private var inputSize = SSD_INPUT_SIZE
    private var numClasses = 80
    
    private var labels: List<String> = emptyList()
    
    @Volatile
    private var isInitialized = false
    private var usingGpu = false
    private var usingNnapi = false
    private var loadedModelName = ""
    
    // Performance metrics
    var lastInferenceTime: Long = 0
        private set
    var lastPreprocessTime: Long = 0
        private set
    var lastPostprocessTime: Long = 0
        private set

    /**
     * Initialize interpreter - tries YOLOv8 first, falls back to SSD
     */
    fun initialize(): Boolean {
        if (isInitialized) return true
        
        // Try to load models in order
        for (modelFile in MODEL_FILES) {
            if (tryLoadModel(modelFile)) {
                loadedModelName = modelFile
                isInitialized = true
                Log.i(TAG, "Model loaded: $modelFile (GPU: $usingGpu, NNAPI: $usingNnapi)")
                return true
            }
        }
        
        Log.e(TAG, "No model could be loaded")
        return false
    }
    
    private fun tryLoadModel(modelFile: String): Boolean {
        try {
            // Check if file exists
            val assetList = context.assets.list("") ?: emptyArray()
            if (!assetList.contains(modelFile)) {
                return false
            }
            
            // Load labels
            labels = loadLabels()
            
            // Configure interpreter options
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                
                // GPU Delegate
                if (useGpu) {
                    try {
                        val compatList = CompatibilityList()
                        if (compatList.isDelegateSupportedOnThisDevice) {
                            val gpuOptions = GpuDelegate.Options().apply {
                                setPrecisionLossAllowed(true)
                                setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER)
                            }
                            gpuDelegate = GpuDelegate(gpuOptions)
                            addDelegate(gpuDelegate)
                            usingGpu = true
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "GPU failed: ${e.message}")
                    }
                }
                
                // NNAPI Delegate
                if (useNnapi && !usingGpu) {
                    try {
                        val nnapiOptions = NnApiDelegate.Options().apply {
                            setAllowFp16(true)
                            setUseNnapiCpu(false)
                        }
                        nnapiDelegate = NnApiDelegate(nnapiOptions)
                        addDelegate(nnapiDelegate)
                        usingNnapi = true
                    } catch (e: Exception) {
                        Log.w(TAG, "NNAPI failed: ${e.message}")
                    }
                }
            }
            
            // Load model
            val modelBuffer = loadModelFile(modelFile)
            interpreter = Interpreter(modelBuffer, options)
            
            // Detect model type from output shape
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            val outputCount = interpreter?.outputTensorCount ?: 0
            
            Log.i(TAG, "Model output: shape=${outputShape?.contentToString()}, count=$outputCount")
            
            // YOLOv8: [1, 84, 8400] or [1, num_classes+4, 8400]
            // SSD: 4 outputs (locations, classes, scores, num_detections)
            isYoloModel = outputCount == 1 && outputShape != null && outputShape.size == 3
            
            if (isYoloModel) {
                inputSize = YOLO_INPUT_SIZE
                numClasses = (outputShape?.get(1) ?: YOLO_NUM_FEATURES) - 4
                Log.i(TAG, "YOLOv8 model detected: $numClasses classes")
            } else {
                inputSize = SSD_INPUT_SIZE
                Log.i(TAG, "SSD model detected")
            }
            
            // Allocate input buffer
            val bufferSize = if (isYoloModel) {
                // YOLOv8 uses float32 input
                4 * inputSize * inputSize * 3
            } else {
                // SSD uses uint8 input
                inputSize * inputSize * 3
            }
            
            inputBuffer = ByteBuffer.allocateDirect(bufferSize).apply {
                order(ByteOrder.nativeOrder())
            }
            
            return true
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $modelFile: ${e.message}")
            return false
        }
    }

    private fun loadLabels(): List<String> {
        // Try custom labels first
        val labelFiles = listOf("labelmap.txt", "labels.txt", "classes.txt")
        
        for (labelFile in labelFiles) {
            try {
                val assetList = context.assets.list("") ?: emptyArray()
                if (assetList.contains(labelFile)) {
                    val labelList = mutableListOf<String>()
                    context.assets.open(labelFile).use { inputStream ->
                        BufferedReader(InputStreamReader(inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (line?.isNotBlank() == true) {
                                    labelList.add(line!!)
                                }
                            }
                        }
                    }
                    Log.i(TAG, "Loaded ${labelList.size} labels from $labelFile")
                    return labelList
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load $labelFile")
            }
        }
        
        // Default to COCO labels
        return listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe",
            "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard",
            "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
            "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl",
            "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza",
            "donut", "cake", "chair", "couch", "potted plant", "bed", "dining table", "toilet",
            "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven",
            "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
            "hair drier", "toothbrush", "helmet", "license_plate"
        )
    }

    private fun loadModelFile(modelFile: String): MappedByteBuffer {
        val assetFd = context.assets.openFd(modelFile)
        val inputStream = FileInputStream(assetFd.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, assetFd.startOffset, assetFd.declaredLength)
    }

    /**
     * Run detection on bitmap
     */
    fun detect(
        bitmap: Bitmap, 
        confidenceThreshold: Float = DEFAULT_CONFIDENCE,
        iouThreshold: Float = DEFAULT_IOU
    ): List<Detection> {
        if (!isInitialized) {
            Log.e(TAG, "Not initialized")
            return emptyList()
        }
        
        return if (isYoloModel) {
            detectYolo(bitmap, confidenceThreshold, iouThreshold)
        } else {
            detectSsd(bitmap, confidenceThreshold)
        }
    }
    
    /**
     * YOLOv8 detection
     */
    private fun detectYolo(
        bitmap: Bitmap,
        confidenceThreshold: Float,
        iouThreshold: Float
    ): List<Detection> {
        // Preprocess
        val preprocessStart = System.currentTimeMillis()
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        fillInputBufferFloat(scaledBitmap)
        lastPreprocessTime = System.currentTimeMillis() - preprocessStart
        
        // Prepare output [1, numClasses+4, 8400]
        val outputSize = numClasses + 4
        val output = Array(1) { Array(outputSize) { FloatArray(YOLO_NUM_PREDICTIONS) } }
        
        // Run inference
        val inferenceStart = System.currentTimeMillis()
        try {
            inputBuffer?.rewind()
            interpreter?.run(inputBuffer, output)
        } catch (e: Exception) {
            Log.e(TAG, "YOLOv8 inference error: ${e.message}", e)
            return emptyList()
        }
        lastInferenceTime = System.currentTimeMillis() - inferenceStart
        
        // Post-process
        val postprocessStart = System.currentTimeMillis()
        val detections = processYoloOutput(
            output[0], 
            confidenceThreshold, 
            bitmap.width, 
            bitmap.height
        )
        
        val nmsDetections = applyNMS(detections, iouThreshold)
        lastPostprocessTime = System.currentTimeMillis() - postprocessStart
        
        if (nmsDetections.isNotEmpty()) {
            Log.d(TAG, "YOLOv8: ${nmsDetections.size} detections in ${getTotalTime()}ms")
        }
        
        return nmsDetections
    }
    
    private fun fillInputBufferFloat(bitmap: Bitmap) {
        inputBuffer?.rewind()
        
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        
        for (pixel in pixels) {
            // Normalize to [0, 1]
            inputBuffer?.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            inputBuffer?.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  
            inputBuffer?.putFloat((pixel and 0xFF) / 255.0f)
        }
    }
    
    private fun processYoloOutput(
        output: Array<FloatArray>,
        threshold: Float,
        imageWidth: Int,
        imageHeight: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        val scaleX = imageWidth.toFloat() / inputSize
        val scaleY = imageHeight.toFloat() / inputSize
        
        for (i in 0 until YOLO_NUM_PREDICTIONS) {
            // Find best class
            var maxScore = 0f
            var maxClassIdx = -1
            
            for (c in 0 until numClasses) {
                val score = output[4 + c][i]
                if (score > maxScore) {
                    maxScore = score
                    maxClassIdx = c
                }
            }
            
            if (maxScore < threshold) continue
            
            // Get bbox (x_center, y_center, width, height)
            val xCenter = output[0][i] * scaleX
            val yCenter = output[1][i] * scaleY
            val width = output[2][i] * scaleX
            val height = output[3][i] * scaleY
            
            val left = xCenter - width / 2
            val top = yCenter - height / 2
            val right = xCenter + width / 2
            val bottom = yCenter + height / 2
            
            // Validate
            if (right <= left || bottom <= top) continue
            
            val className = if (maxClassIdx in labels.indices) labels[maxClassIdx] else "class_$maxClassIdx"
            
            detections.add(Detection(
                classId = maxClassIdx,
                className = className,
                confidence = maxScore,
                boundingBox = BoundingBox(
                    max(0f, left),
                    max(0f, top),
                    min(imageWidth.toFloat(), right),
                    min(imageHeight.toFloat(), bottom)
                )
            ))
        }
        
        return detections
    }
    
    /**
     * SSD MobileNet detection
     */
    private fun detectSsd(bitmap: Bitmap, confidenceThreshold: Float): List<Detection> {
        Log.d(TAG, "SSD: Starting detection on ${bitmap.width}x${bitmap.height} image")
        
        // Preprocess
        val preprocessStart = System.currentTimeMillis()
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        fillInputBufferUint8(scaledBitmap)
        lastPreprocessTime = System.currentTimeMillis() - preprocessStart
        
        // Get output tensor info
        val numOutputs = interpreter?.outputTensorCount ?: 0
        Log.d(TAG, "SSD: Model has $numOutputs output tensors")
        
        for (i in 0 until numOutputs) {
            val tensor = interpreter?.getOutputTensor(i)
            Log.d(TAG, "  Output $i: shape=${tensor?.shape()?.contentToString()}, dtype=${tensor?.dataType()}")
        }
        
        // Prepare outputs - SSD MobileNet typically has:
        // [0] locations: [1, num_detections, 4]
        // [1] classes: [1, num_detections]
        // [2] scores: [1, num_detections]
        // [3] num_detections: [1]
        val outputLocations = Array(1) { Array(SSD_MAX_DETECTIONS) { FloatArray(4) } }
        val outputClasses = Array(1) { FloatArray(SSD_MAX_DETECTIONS) }
        val outputScores = Array(1) { FloatArray(SSD_MAX_DETECTIONS) }
        val numDetections = FloatArray(1)
        
        val outputMap = HashMap<Int, Any>()
        outputMap[0] = outputLocations
        outputMap[1] = outputClasses
        outputMap[2] = outputScores
        outputMap[3] = numDetections
        
        // Run inference
        val inferenceStart = System.currentTimeMillis()
        try {
            inputBuffer?.rewind()
            interpreter?.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)
        } catch (e: Exception) {
            Log.e(TAG, "SSD inference error: ${e.message}", e)
            return emptyList()
        }
        lastInferenceTime = System.currentTimeMillis() - inferenceStart
        
        Log.d(TAG, "SSD: Inference done in ${lastInferenceTime}ms, numDetections=${numDetections[0]}")
        
        // Log first few detections for debugging
        val detCount = min(SSD_MAX_DETECTIONS, numDetections[0].toInt())
        for (i in 0 until min(3, detCount)) {
            Log.d(TAG, "  Det $i: class=${outputClasses[0][i].toInt()}, score=${outputScores[0][i]}, box=${outputLocations[0][i].contentToString()}")
        }
        
        // Post-process
        val postprocessStart = System.currentTimeMillis()
        val detections = processSsdOutput(
            outputLocations[0],
            outputClasses[0],
            outputScores[0],
            numDetections[0].toInt(),
            confidenceThreshold,
            bitmap.width,
            bitmap.height
        )
        lastPostprocessTime = System.currentTimeMillis() - postprocessStart
        
        Log.d(TAG, "SSD: ${detections.size} detections above ${confidenceThreshold} threshold in ${getTotalTime()}ms")
        
        return detections
    }
    
    private fun fillInputBufferUint8(bitmap: Bitmap) {
        inputBuffer?.rewind()
        
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        
        for (pixel in pixels) {
            inputBuffer?.put(((pixel shr 16) and 0xFF).toByte())
            inputBuffer?.put(((pixel shr 8) and 0xFF).toByte())
            inputBuffer?.put((pixel and 0xFF).toByte())
        }
    }
    
    private fun processSsdOutput(
        locations: Array<FloatArray>,
        classes: FloatArray,
        scores: FloatArray,
        count: Int,
        threshold: Float,
        imageWidth: Int,
        imageHeight: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        val numResults = min(count, SSD_MAX_DETECTIONS)
        
        for (i in 0 until numResults) {
            val score = scores[i]
            if (score < threshold) continue
            
            val classId = classes[i].toInt()
            val className = if (classId in labels.indices) labels[classId] else "class_$classId"
            
            // Skip placeholder classes
            if (className == "???" || className.isEmpty()) continue
            
            // SSD: [top, left, bottom, right] normalized
            val top = locations[i][0] * imageHeight
            val left = locations[i][1] * imageWidth
            val bottom = locations[i][2] * imageHeight
            val right = locations[i][3] * imageWidth
            
            if (right <= left || bottom <= top) continue
            
            detections.add(Detection(
                classId = classId,
                className = className,
                confidence = score,
                boundingBox = BoundingBox(
                    max(0f, left),
                    max(0f, top),
                    min(imageWidth.toFloat(), right),
                    min(imageHeight.toFloat(), bottom)
                )
            ))
        }
        
        return detections
    }

    private fun applyNMS(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        
        val sorted = detections.sortedByDescending { it.confidence }
        val selected = mutableListOf<Detection>()
        
        for (detection in sorted) {
            var shouldSelect = true
            for (selectedDetection in selected) {
                if (detection.className == selectedDetection.className) {
                    val iou = detection.boundingBox.iou(selectedDetection.boundingBox)
                    if (iou > iouThreshold) {
                        shouldSelect = false
                        break
                    }
                }
            }
            if (shouldSelect) {
                selected.add(detection)
            }
        }
        
        return selected
    }

    fun getTotalTime(): Long = lastPreprocessTime + lastInferenceTime + lastPostprocessTime
    
    fun getFps(): Float {
        val total = getTotalTime()
        return if (total > 0) 1000f / total else 0f
    }
    
    fun isUsingGpu(): Boolean = usingGpu
    fun isUsingNnapi(): Boolean = usingNnapi
    fun getModelName(): String = loadedModelName
    fun isYoloModel(): Boolean = isYoloModel
    fun getNumClasses(): Int = numClasses
    fun getLabels(): List<String> = labels

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
        nnapiDelegate?.close()
        
        interpreter = null
        gpuDelegate = null
        nnapiDelegate = null
        inputBuffer = null
        
        isInitialized = false
        Log.i(TAG, "Closed")
    }
}
