package com.oakcraft.lumen.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lumen_settings")

/**
 * Every user-visible preference in one place. Nothing here is synced off-device.
 */
class LumenSettings(private val context: Context) {

    private object Keys {
        val SHIELD_ENABLED = booleanPreferencesKey("shield_enabled")
        val SHIELD_AUTOSTART = booleanPreferencesKey("shield_autostart")
        val UPSTREAM_DNS = stringPreferencesKey("upstream_dns")
        val ACTIVE_SOURCES = stringSetPreferencesKey("active_sources")
        val USER_DENY = stringSetPreferencesKey("user_deny")
        val USER_ALLOW = stringSetPreferencesKey("user_allow")
        val BYPASS_PACKAGES = stringSetPreferencesKey("bypass_packages")
        val LAST_LIST_UPDATE = longPreferencesKey("last_list_update")
        val BLOCKED_TOTAL = longPreferencesKey("blocked_total")
        val QUERIES_TOTAL = longPreferencesKey("queries_total")

        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val RESUME_ON_HEADSET = booleanPreferencesKey("resume_on_headset")
        val SLEEP_TIMER_MINUTES = longPreferencesKey("sleep_timer_minutes")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }

    val shieldEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHIELD_ENABLED] ?: false }
    val shieldAutostart: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHIELD_AUTOSTART] ?: false }
    val upstreamDns: Flow<String> = context.dataStore.data.map { it[Keys.UPSTREAM_DNS] ?: DEFAULT_UPSTREAM }
    val activeSources: Flow<Set<String>> = context.dataStore.data.map {
        it[Keys.ACTIVE_SOURCES] ?: BlocklistSource.DEFAULT_IDS
    }
    val userDeny: Flow<Set<String>> = context.dataStore.data.map { it[Keys.USER_DENY] ?: emptySet() }
    val userAllow: Flow<Set<String>> = context.dataStore.data.map { it[Keys.USER_ALLOW] ?: emptySet() }
    val bypassPackages: Flow<Set<String>> = context.dataStore.data.map { it[Keys.BYPASS_PACKAGES] ?: emptySet() }
    val lastListUpdate: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_LIST_UPDATE] ?: 0L }
    val blockedTotal: Flow<Long> = context.dataStore.data.map { it[Keys.BLOCKED_TOTAL] ?: 0L }
    val queriesTotal: Flow<Long> = context.dataStore.data.map { it[Keys.QUERIES_TOTAL] ?: 0L }

    val playbackSpeed: Flow<Float> = context.dataStore.data.map { it[Keys.PLAYBACK_SPEED] ?: 1.0f }
    val skipSilence: Flow<Boolean> = context.dataStore.data.map { it[Keys.SKIP_SILENCE] ?: false }
    val resumeOnHeadset: Flow<Boolean> = context.dataStore.data.map { it[Keys.RESUME_ON_HEADSET] ?: true }
    val sleepTimerMinutes: Flow<Long> = context.dataStore.data.map { it[Keys.SLEEP_TIMER_MINUTES] ?: 30L }
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: true }

    suspend fun setShieldEnabled(value: Boolean) = edit { it[Keys.SHIELD_ENABLED] = value }
    suspend fun setShieldAutostart(value: Boolean) = edit { it[Keys.SHIELD_AUTOSTART] = value }
    suspend fun setUpstreamDns(value: String) = edit { it[Keys.UPSTREAM_DNS] = value }
    suspend fun setActiveSources(value: Set<String>) = edit { it[Keys.ACTIVE_SOURCES] = value }
    suspend fun setUserDeny(value: Set<String>) = edit { it[Keys.USER_DENY] = value }
    suspend fun setUserAllow(value: Set<String>) = edit { it[Keys.USER_ALLOW] = value }
    suspend fun setBypassPackages(value: Set<String>) = edit { it[Keys.BYPASS_PACKAGES] = value }
    suspend fun setLastListUpdate(value: Long) = edit { it[Keys.LAST_LIST_UPDATE] = value }

    suspend fun setPlaybackSpeed(value: Float) = edit { it[Keys.PLAYBACK_SPEED] = value }
    suspend fun setSkipSilence(value: Boolean) = edit { it[Keys.SKIP_SILENCE] = value }
    suspend fun setResumeOnHeadset(value: Boolean) = edit { it[Keys.RESUME_ON_HEADSET] = value }
    suspend fun setSleepTimerMinutes(value: Long) = edit { it[Keys.SLEEP_TIMER_MINUTES] = value }
    suspend fun setDynamicColor(value: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = value }

    /** Counters are folded in periodically rather than per-query, to keep disk writes rare. */
    suspend fun addCounters(queries: Long, blocked: Long) = edit {
        it[Keys.QUERIES_TOTAL] = (it[Keys.QUERIES_TOTAL] ?: 0L) + queries
        it[Keys.BLOCKED_TOTAL] = (it[Keys.BLOCKED_TOTAL] ?: 0L) + blocked
    }

    suspend fun resetCounters() = edit {
        it[Keys.QUERIES_TOTAL] = 0L
        it[Keys.BLOCKED_TOTAL] = 0L
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    companion object {
        const val DEFAULT_UPSTREAM = "1.1.1.1"
    }
}
