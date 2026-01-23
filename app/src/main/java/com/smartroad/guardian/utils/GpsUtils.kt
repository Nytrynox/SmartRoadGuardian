package com.smartroad.guardian.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * GPS Utility for location tracking
 */
class GpsUtils(private val context: Context) {
    
    companion object {
        private const val TAG = "GpsUtils"
        private const val UPDATE_INTERVAL = 5000L  // 5 seconds
        private const val FASTEST_INTERVAL = 2000L  // 2 seconds
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    
    private var locationCallback: LocationCallback? = null
    private var isTracking = false
    
    @Volatile
    var lastLocation: Location? = null
        private set

    /**
     * Check if location permission is granted
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get last known location (one-shot)
     */
    @SuppressLint("MissingPermission")
    suspend fun getLastLocation(): Location? = suspendCoroutine { continuation ->
        if (!hasLocationPermission()) {
            continuation.resume(null)
            return@suspendCoroutine
        }
        
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                lastLocation = location
                continuation.resume(location)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to get location: ${e.message}")
                continuation.resume(null)
            }
    }

    /**
     * Start location updates as Flow
     */
    @SuppressLint("MissingPermission")
    fun getLocationUpdates(): Flow<Location> = callbackFlow {
        if (!hasLocationPermission()) {
            close()
            return@callbackFlow
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL
        )
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    lastLocation = location
                    trySend(location)
                }
            }
        }
        
        locationCallback = callback
        isTracking = true

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            callback,
            Looper.getMainLooper()
        )

        awaitClose {
            stopLocationUpdates()
        }
    }

    /**
     * Start continuous tracking
     */
    @SuppressLint("MissingPermission")
    fun startTracking(onLocationUpdate: (Location) -> Unit) {
        if (!hasLocationPermission() || isTracking) return

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL
        )
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    lastLocation = location
                    onLocationUpdate(location)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )
        
        isTracking = true
        Log.i(TAG, "Location tracking started")
    }

    /**
     * Stop location updates
     */
    fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
        isTracking = false
        Log.i(TAG, "Location tracking stopped")
    }

    /**
     * Format location for display
     */
    fun formatLocation(location: Location?): String {
        return location?.let {
            String.format("%.6f, %.6f", it.latitude, it.longitude)
        } ?: "Unknown"
    }

    /**
     * Calculate distance between two locations in meters
     */
    fun distanceBetween(loc1: Location, loc2: Location): Float {
        return loc1.distanceTo(loc2)
    }
}
