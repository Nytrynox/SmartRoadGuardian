package com.smartroad.guardian.ml

import android.util.Log
import kotlin.math.sqrt

/**
 * ByteTrack-inspired Object Tracker
 * 
 * Tracks objects across frames using:
 * - IOU matching
 * - Object ID persistence
 * - Motion vector calculation
 * 
 * As per specification - used for:
 * - Rider counting
 * - Motion direction (wrong-way detection)
 * - Triple riding logic
 */
class TrackerEngine {
    companion object {
        private const val TAG = "TrackerEngine"
        private const val MAX_AGE = 5  // Frames before track is removed
        private const val DISTANCE_THRESHOLD = 100f
        private const val IOU_THRESHOLD = 0.3f
    }
    
    private val tracks = mutableMapOf<Int, Track>()
    private var nextTrackId = 1
    
    /**
     * Tracked object with motion history
     */
    data class Track(
        val id: Int,
        var detection: Detection,
        var age: Int = 0,
        var history: MutableList<Pair<Float, Float>> = mutableListOf(),
        var motionVector: Pair<Float, Float> = Pair(0f, 0f)
    ) {
        fun addPosition(x: Float, y: Float) {
            history.add(Pair(x, y))
            if (history.size > 10) {
                history.removeAt(0)
            }
            updateMotionVector()
        }
        
        private fun updateMotionVector() {
            if (history.size >= 2) {
                val last = history.last()
                val prev = history[history.size - 2]
                motionVector = Pair(last.first - prev.first, last.second - prev.second)
            }
        }
        
        fun getDirection(): Direction {
            val (dx, dy) = motionVector
            val magnitude = sqrt(dx * dx + dy * dy)
            
            if (magnitude < 5f) return Direction.STATIONARY
            
            return when {
                dy < -10f -> Direction.UP
                dy > 10f -> Direction.DOWN
                dx < -10f -> Direction.LEFT
                dx > 10f -> Direction.RIGHT
                else -> Direction.STATIONARY
            }
        }
    }
    
    enum class Direction {
        UP, DOWN, LEFT, RIGHT, STATIONARY
    }
    
    /**
     * Update tracks with new detections
     * Returns detections with track IDs assigned
     */
    fun update(detections: List<Detection>): List<Detection> {
        // Age existing tracks
        tracks.values.forEach { it.age++ }
        
        val trackedDetections = mutableListOf<Detection>()
        val matchedTrackIds = mutableSetOf<Int>()
        val unmatchedDetections = mutableListOf<Detection>()
        
        // Match detections to existing tracks
        for (detection in detections) {
            var bestMatch: Track? = null
            var bestScore = 0f
            
            for ((_, track) in tracks) {
                if (track.id in matchedTrackIds) continue
                if (track.detection.className != detection.className) continue
                
                val iouScore = detection.boundingBox.iou(track.detection.boundingBox)
                val distScore = 1f - (calculateDistance(detection, track.detection) / 500f).coerceIn(0f, 1f)
                val score = iouScore * 0.7f + distScore * 0.3f
                
                if (score > bestScore && (iouScore > IOU_THRESHOLD || score > 0.5f)) {
                    bestMatch = track
                    bestScore = score
                }
            }
            
            if (bestMatch != null) {
                // Update existing track
                bestMatch.detection = detection
                bestMatch.age = 0
                bestMatch.addPosition(detection.boundingBox.centerX(), detection.boundingBox.centerY())
                matchedTrackIds.add(bestMatch.id)
                
                trackedDetections.add(detection.copy(trackId = bestMatch.id))
            } else {
                unmatchedDetections.add(detection)
            }
        }
        
        // Create new tracks for unmatched detections
        for (detection in unmatchedDetections) {
            val newTrack = Track(
                id = nextTrackId++,
                detection = detection
            )
            newTrack.addPosition(detection.boundingBox.centerX(), detection.boundingBox.centerY())
            tracks[newTrack.id] = newTrack
            
            trackedDetections.add(detection.copy(trackId = newTrack.id))
        }
        
        // Remove old tracks
        val removed = tracks.entries.removeAll { it.value.age > MAX_AGE }
        if (removed) {
            Log.d(TAG, "Removed stale tracks, active: ${tracks.size}")
        }
        
        return trackedDetections
    }
    
    private fun calculateDistance(d1: Detection, d2: Detection): Float {
        val cx1 = d1.boundingBox.centerX()
        val cy1 = d1.boundingBox.centerY()
        val cx2 = d2.boundingBox.centerX()
        val cy2 = d2.boundingBox.centerY()
        return sqrt((cx1 - cx2) * (cx1 - cx2) + (cy1 - cy2) * (cy1 - cy2))
    }
    
    /**
     * Get motion direction for a tracked object
     */
    fun getTrackDirection(trackId: Int): Direction {
        return tracks[trackId]?.getDirection() ?: Direction.STATIONARY
    }
    
    /**
     * Get motion vector for a tracked object
     */
    fun getTrackMotion(trackId: Int): Pair<Float, Float> {
        return tracks[trackId]?.motionVector ?: Pair(0f, 0f)
    }
    
    /**
     * Get all active tracks
     */
    fun getTracks(): Map<Int, Track> = tracks.toMap()
    
    /**
     * Get track count by class
     */
    fun countByClass(className: String): Int {
        return tracks.values.count { it.detection.className == className && it.age == 0 }
    }
    
    /**
     * Reset all tracks
     */
    fun reset() {
        tracks.clear()
        nextTrackId = 1
    }
}
