package com.smartroad.guardian.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "smartroad_settings")

/**
 * Preferences Manager using DataStore
 * 
 * Stores all app settings per PRD requirements
 */
class PreferencesManager(private val context: Context) {
    
    companion object {
        // Detection settings
        private val CONFIDENCE_THRESHOLD = floatPreferencesKey("confidence_threshold")
        private val HELMET_DETECTION_ENABLED = booleanPreferencesKey("helmet_detection_enabled")
        private val TRIPLE_RIDING_ENABLED = booleanPreferencesKey("triple_riding_enabled")
        private val PLATE_DETECTION_ENABLED = booleanPreferencesKey("plate_detection_enabled")
        private val WRONG_WAY_ENABLED = booleanPreferencesKey("wrong_way_enabled")
        
        // Feedback settings
        private val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        private val AUDIO_ENABLED = booleanPreferencesKey("audio_enabled")
        
        // Email settings
        private val EMAIL_RECIPIENT = stringPreferencesKey("email_recipient")
        private val SMTP_SERVER = stringPreferencesKey("smtp_server")
        private val SMTP_PORT = intPreferencesKey("smtp_port")
        private val SMTP_USERNAME = stringPreferencesKey("smtp_username")
        private val SMTP_PASSWORD = stringPreferencesKey("smtp_password")
        
        // Performance settings
        private val BATTERY_SAVER_MODE = booleanPreferencesKey("battery_saver_mode")
        private val MAX_STORAGE_MB = intPreferencesKey("max_storage_mb")
        
        // Stats
        private val TOTAL_SESSIONS = intPreferencesKey("total_sessions")
        private val TOTAL_VIOLATIONS_ALL_TIME = intPreferencesKey("total_violations_all_time")
    }
    
    // Detection settings
    val confidenceThreshold: Flow<Float> = context.dataStore.data.map { 
        it[CONFIDENCE_THRESHOLD] ?: 0.5f 
    }
    
    val helmetDetectionEnabled: Flow<Boolean> = context.dataStore.data.map { 
        it[HELMET_DETECTION_ENABLED] ?: true 
    }
    
    val tripleRidingEnabled: Flow<Boolean> = context.dataStore.data.map { 
        it[TRIPLE_RIDING_ENABLED] ?: true 
    }
    
    val plateDetectionEnabled: Flow<Boolean> = context.dataStore.data.map { 
        it[PLATE_DETECTION_ENABLED] ?: true 
    }
    
    val wrongWayEnabled: Flow<Boolean> = context.dataStore.data.map { 
        it[WRONG_WAY_ENABLED] ?: true 
    }
    
    // Feedback settings
    val vibrationEnabled: Flow<Boolean> = context.dataStore.data.map { 
        it[VIBRATION_ENABLED] ?: true 
    }
    
    val audioEnabled: Flow<Boolean> = context.dataStore.data.map { 
        it[AUDIO_ENABLED] ?: true 
    }
    
    // Email settings
    val emailRecipient: Flow<String> = context.dataStore.data.map { 
        it[EMAIL_RECIPIENT] ?: "" 
    }
    
    val smtpServer: Flow<String> = context.dataStore.data.map { 
        it[SMTP_SERVER] ?: "smtp.gmail.com" 
    }
    
    val smtpPort: Flow<Int> = context.dataStore.data.map { 
        it[SMTP_PORT] ?: 587 
    }
    
    val smtpUsername: Flow<String> = context.dataStore.data.map { 
        it[SMTP_USERNAME] ?: "" 
    }
    
    val smtpPassword: Flow<String> = context.dataStore.data.map { 
        it[SMTP_PASSWORD] ?: "" 
    }
    
    // Performance settings
    val batterySaverMode: Flow<Boolean> = context.dataStore.data.map { 
        it[BATTERY_SAVER_MODE] ?: false 
    }
    
    val maxStorageMb: Flow<Int> = context.dataStore.data.map { 
        it[MAX_STORAGE_MB] ?: 2000 
    }
    
    // Stats
    val totalSessions: Flow<Int> = context.dataStore.data.map { 
        it[TOTAL_SESSIONS] ?: 0 
    }
    
    val totalViolationsAllTime: Flow<Int> = context.dataStore.data.map { 
        it[TOTAL_VIOLATIONS_ALL_TIME] ?: 0 
    }
    
    // Setters
    suspend fun setConfidenceThreshold(value: Float) {
        context.dataStore.edit { it[CONFIDENCE_THRESHOLD] = value.coerceIn(0.3f, 0.9f) }
    }
    
    suspend fun setHelmetDetectionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[HELMET_DETECTION_ENABLED] = enabled }
    }
    
    suspend fun setTripleRidingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[TRIPLE_RIDING_ENABLED] = enabled }
    }
    
    suspend fun setPlateDetectionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PLATE_DETECTION_ENABLED] = enabled }
    }
    
    suspend fun setWrongWayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[WRONG_WAY_ENABLED] = enabled }
    }
    
    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[VIBRATION_ENABLED] = enabled }
    }
    
    suspend fun setAudioEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUDIO_ENABLED] = enabled }
    }
    
    suspend fun setEmailRecipient(email: String) {
        context.dataStore.edit { it[EMAIL_RECIPIENT] = email }
    }
    
    suspend fun setSmtpServer(server: String) {
        context.dataStore.edit { it[SMTP_SERVER] = server }
    }
    
    suspend fun setSmtpPort(port: Int) {
        context.dataStore.edit { it[SMTP_PORT] = port }
    }
    
    suspend fun setSmtpUsername(username: String) {
        context.dataStore.edit { it[SMTP_USERNAME] = username }
    }
    
    suspend fun setSmtpPassword(password: String) {
        context.dataStore.edit { it[SMTP_PASSWORD] = password }
    }
    
    suspend fun setBatterySaverMode(enabled: Boolean) {
        context.dataStore.edit { it[BATTERY_SAVER_MODE] = enabled }
    }
    
    suspend fun setMaxStorageMb(mb: Int) {
        context.dataStore.edit { it[MAX_STORAGE_MB] = mb }
    }
    
    suspend fun incrementSessions() {
        context.dataStore.edit { 
            val current = it[TOTAL_SESSIONS] ?: 0
            it[TOTAL_SESSIONS] = current + 1
        }
    }
    
    suspend fun addViolations(count: Int) {
        context.dataStore.edit { 
            val current = it[TOTAL_VIOLATIONS_ALL_TIME] ?: 0
            it[TOTAL_VIOLATIONS_ALL_TIME] = current + count
        }
    }
    
    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
