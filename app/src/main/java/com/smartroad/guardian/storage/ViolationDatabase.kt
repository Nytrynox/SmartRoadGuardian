package com.smartroad.guardian.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room Database for SmartRoad Guardian
 */
@Database(
    entities = [ViolationEntity::class],
    version = 1,
    exportSchema = true
)
abstract class ViolationDatabase : RoomDatabase() {
    
    abstract fun violationDao(): ViolationDao
    
    companion object {
        private const val DATABASE_NAME = "smartroad_guardian.db"
        
        @Volatile
        private var INSTANCE: ViolationDatabase? = null
        
        fun getInstance(context: Context): ViolationDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ViolationDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
