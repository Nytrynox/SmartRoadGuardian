package com.smartroad.guardian.storage

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Violation Entity for Room Database
 */
@Entity(
    tableName = "violations",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["type"]),
        Index(value = ["is_synced"])
    ]
)
data class ViolationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "type")
    val type: String,
    
    @ColumnInfo(name = "confidence")
    val confidence: Float,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    
    @ColumnInfo(name = "latitude")
    val latitude: Double,
    
    @ColumnInfo(name = "longitude")
    val longitude: Double,
    
    @ColumnInfo(name = "image_path")
    val imagePath: String,
    
    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String? = null,
    
    @ColumnInfo(name = "is_synced")
    val isSynced: Boolean = false,
    
    @ColumnInfo(name = "track_id")
    val trackId: Int = -1,
    
    @ColumnInfo(name = "bbox_left")
    val bboxLeft: Float = 0f,
    
    @ColumnInfo(name = "bbox_top")
    val bboxTop: Float = 0f,
    
    @ColumnInfo(name = "bbox_right")
    val bboxRight: Float = 0f,
    
    @ColumnInfo(name = "bbox_bottom")
    val bboxBottom: Float = 0f,
    
    @ColumnInfo(name = "notes")
    val notes: String? = null
)

/**
 * Statistics for dashboard
 */
data class ViolationStats(
    val type: String,
    val count: Int
)

/**
 * Daily summary
 */
data class DailySummary(
    val date: String,
    val totalCount: Int
)
