package com.smartroad.guardian.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for ViolationEntity
 * 
 * Provides all CRUD operations and query methods
 */
@Dao
interface ViolationDao {
    
    // Insert
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(violation: ViolationEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(violations: List<ViolationEntity>)
    
    // Query - Flow
    @Query("SELECT * FROM violations ORDER BY timestamp DESC")
    fun getAllViolations(): Flow<List<ViolationEntity>>
    
    @Query("SELECT * FROM violations WHERE timestamp >= :startOfDay ORDER BY timestamp DESC")
    fun getTodayViolations(startOfDay: Long): Flow<List<ViolationEntity>>
    
    @Query("SELECT * FROM violations WHERE type = :type ORDER BY timestamp DESC")
    fun getByType(type: String): Flow<List<ViolationEntity>>
    
    @Query("SELECT COUNT(*) FROM violations")
    fun getTotalCount(): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM violations WHERE timestamp >= :startOfDay")
    fun getTodayCount(startOfDay: Long): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM violations WHERE is_synced = 0")
    fun getUnsyncedCount(): Flow<Int>
    
    // Query - Suspend
    @Query("SELECT * FROM violations ORDER BY timestamp DESC")
    suspend fun getAllViolationsList(): List<ViolationEntity>
    
    @Query("SELECT * FROM violations WHERE id = :id")
    suspend fun getById(id: Long): ViolationEntity?
    
    @Query("SELECT * FROM violations WHERE is_synced = 0 ORDER BY timestamp DESC")
    suspend fun getUnsynced(): List<ViolationEntity>
    
    @Query("SELECT COUNT(*) FROM violations")
    suspend fun getTotalCountSync(): Int
    
    @Query("SELECT * FROM violations WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    suspend fun getByDateRange(start: Long, end: Long): List<ViolationEntity>
    
    // Stats
    @Query("SELECT type, COUNT(*) as count FROM violations GROUP BY type")
    fun getViolationStats(): Flow<List<ViolationStat>>
    
    @Query("""
        SELECT 
            strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime') as day,
            COUNT(*) as count
        FROM violations
        WHERE timestamp >= :since
        GROUP BY strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime')
        ORDER BY day DESC
    """)
    suspend fun getDailyStats(since: Long): List<DailyStat>
    
    // Update
    @Update
    suspend fun update(violation: ViolationEntity)
    
    @Query("UPDATE violations SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)
    
    @Query("UPDATE violations SET is_synced = 0 WHERE id IN (:ids)")
    suspend fun markAsUnsynced(ids: List<Long>)
    
    // Delete
    @Query("DELETE FROM violations WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    @Query("DELETE FROM violations WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
    
    @Query("DELETE FROM violations WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int
    
    @Query("DELETE FROM violations")
    suspend fun deleteAll()
    
    // Cleanup - delete oldest when over limit
    @Query("""
        DELETE FROM violations WHERE id IN (
            SELECT id FROM violations ORDER BY timestamp ASC LIMIT :count
        )
    """)
    suspend fun deleteOldest(count: Int)
}

/**
 * Aggregate stats by violation type
 */
data class ViolationStat(
    val type: String,
    val count: Int
)

/**
 * Daily statistics
 */
data class DailyStat(
    val day: String,
    val count: Int
)
