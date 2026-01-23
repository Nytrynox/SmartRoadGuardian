package com.smartroad.guardian.logic

import android.util.Log
import com.smartroad.guardian.ml.Detection

/**
 * Violation Detection Engine
 * 
 * Detects:
 * - Helmet violations (rider/pillion without helmet)
 * - Triple riding (>2 people on motorcycle)
 * - Number plate missing/obscured
 * 
 * NO FAKE DATA. REAL LOGIC ONLY.
 */
class ViolationEngine {
    companion object {
        private const val TAG = "ViolationEngine"
        
        // Debounce - prevent same violation firing repeatedly
        private const val VIOLATION_COOLDOWN_MS = 5000L
    }
    
    private val recentViolations = mutableMapOf<String, Long>()
    
    /**
     * Violation types
     */
    enum class ViolationType(val displayName: String, val severity: Int) {
        NO_HELMET("Helmet Missing", 3),
        TRIPLE_RIDING("Triple Riding", 3),
        NO_PLATE("Number Plate Missing", 2)
    }
    
    /**
     * Violation result with evidence
     */
    data class Violation(
        val type: ViolationType,
        val confidence: Float,
        val details: String,
        val relatedDetections: List<Detection>,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Analyze detections for violations
     */
    fun analyze(detections: List<Detection>): List<Violation> {
        val violations = mutableListOf<Violation>()
        val currentTime = System.currentTimeMillis()
        
        // Filter motorcycles
        val motorcycles = detections.filter { 
            it.className.lowercase() in listOf("motorcycle", "motorbike")
        }
        
        for (motorcycle in motorcycles) {
            // Check helmet violation
            val helmetViolation = checkHelmetViolation(motorcycle, detections)
            if (helmetViolation != null && canReportViolation("helmet_${motorcycle.trackId}", currentTime)) {
                violations.add(helmetViolation)
                Log.i(TAG, "VIOLATION: ${helmetViolation.type.displayName}")
            }
            
            // Check triple riding
            val tripleRiding = checkTripleRiding(motorcycle, detections)
            if (tripleRiding != null && canReportViolation("triple_${motorcycle.trackId}", currentTime)) {
                violations.add(tripleRiding)
                Log.i(TAG, "VIOLATION: ${tripleRiding.type.displayName}")
            }
            
            // Check plate presence
            val noPlate = checkPlatePresence(motorcycle, detections)
            if (noPlate != null && canReportViolation("plate_${motorcycle.trackId}", currentTime)) {
                violations.add(noPlate)
                Log.i(TAG, "VIOLATION: ${noPlate.type.displayName}")
            }
        }
        
        return violations
    }
    
    /**
     * Helmet Violation Logic:
     * - Find persons spatially associated with motorcycle
     * - Check if helmet exists above each person's head region
     */
    private fun checkHelmetViolation(motorcycle: Detection, allDetections: List<Detection>): Violation? {
        val riders = findRidersOnMotorcycle(motorcycle, allDetections)
        if (riders.isEmpty()) return null
        
        val helmets = allDetections.filter { it.className.lowercase() == "helmet" }
        
        for (rider in riders) {
            val hasHelmet = helmets.any { helmet -> 
                isHelmetAboveHead(helmet, rider)
            }
            
            if (!hasHelmet) {
                return Violation(
                    type = ViolationType.NO_HELMET,
                    confidence = rider.confidence,
                    details = "Rider without helmet detected",
                    relatedDetections = listOf(motorcycle, rider)
                )
            }
        }
        
        return null
    }
    
    /**
     * Triple Riding Logic:
     * - Count persons spatially associated with motorcycle
     * - If > 2, triple riding violation
     */
    private fun checkTripleRiding(motorcycle: Detection, allDetections: List<Detection>): Violation? {
        val riders = findRidersOnMotorcycle(motorcycle, allDetections)
        
        if (riders.size > 2) {
            return Violation(
                type = ViolationType.TRIPLE_RIDING,
                confidence = riders.minOfOrNull { it.confidence } ?: 0.5f,
                details = "${riders.size} riders detected on motorcycle",
                relatedDetections = listOf(motorcycle) + riders
            )
        }
        
        return null
    }
    
    /**
     * Plate Presence Logic:
     * - Check if license plate exists near motorcycle rear
     */
    private fun checkPlatePresence(motorcycle: Detection, allDetections: List<Detection>): Violation? {
        val plates = allDetections.filter { 
            it.className.lowercase() in listOf("license_plate", "number plate", "plate")
        }
        
        val hasPlate = plates.any { plate ->
            isPlateNearVehicle(plate, motorcycle)
        }
        
        if (!hasPlate) {
            return Violation(
                type = ViolationType.NO_PLATE,
                confidence = motorcycle.confidence,
                details = "No license plate detected on vehicle",
                relatedDetections = listOf(motorcycle)
            )
        }
        
        return null
    }
    
    /**
     * Find persons spatially associated with a motorcycle
     */
    private fun findRidersOnMotorcycle(motorcycle: Detection, allDetections: List<Detection>): List<Detection> {
        val mcBox = motorcycle.boundingBox
        val mcWidth = mcBox.right - mcBox.left
        val mcHeight = mcBox.bottom - mcBox.top
        
        return allDetections.filter { det ->
            if (det.className.lowercase() != "person") return@filter false
            
            val personBox = det.boundingBox
            val pCenterX = personBox.centerX()
            val pCenterY = personBox.centerY()
            
            // Person should be within expanded motorcycle region
            val margin = 0.3f
            val xMin = mcBox.left - mcWidth * margin
            val xMax = mcBox.right + mcWidth * margin
            val yMin = mcBox.top - mcHeight * margin
            val yMax = mcBox.bottom + mcHeight * 0.1f
            
            pCenterX in xMin..xMax && pCenterY in yMin..yMax
        }
    }
    
    /**
     * Check if helmet is above person's head
     */
    private fun isHelmetAboveHead(helmet: Detection, person: Detection): Boolean {
        val helmetCenterY = helmet.boundingBox.centerY()
        val helmetCenterX = helmet.boundingBox.centerX()
        
        val personTop = person.boundingBox.top
        val personHeight = person.boundingBox.bottom - person.boundingBox.top
        val personCenterX = person.boundingBox.centerX()
        val personWidth = person.boundingBox.right - person.boundingBox.left
        
        // Helmet should be in top 35% of person bbox and horizontally aligned
        val inTopRegion = helmetCenterY < personTop + personHeight * 0.35f
        val horizontallyAligned = kotlin.math.abs(helmetCenterX - personCenterX) < personWidth * 0.6f
        
        return inTopRegion && horizontallyAligned
    }
    
    /**
     * Check if plate is near vehicle
     */
    private fun isPlateNearVehicle(plate: Detection, vehicle: Detection): Boolean {
        val plateBox = plate.boundingBox
        val vehicleBox = vehicle.boundingBox
        
        val plateCenter = Pair(plateBox.centerX(), plateBox.centerY())
        
        val vehicleBottom = vehicleBox.bottom
        val vehicleLeft = vehicleBox.left
        val vehicleRight = vehicleBox.right
        val vehicleHeight = vehicleBox.bottom - vehicleBox.top
        
        val nearBottom = plateCenter.second > vehicleBottom - vehicleHeight * 0.4f
        val horizontallyWithin = plateCenter.first in vehicleLeft..vehicleRight
        
        return nearBottom && horizontallyWithin
    }
    
    /**
     * Check if violation can be reported (debounce)
     */
    private fun canReportViolation(key: String, currentTime: Long): Boolean {
        val lastReported = recentViolations[key] ?: 0L
        if (currentTime - lastReported < VIOLATION_COOLDOWN_MS) {
            return false
        }
        recentViolations[key] = currentTime
        return true
    }
    
    /**
     * Clear violation history
     */
    fun reset() {
        recentViolations.clear()
    }
}
