package com.oakcraft.lumen.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oakcraft.lumen.LumenApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LumenApp
    val settings = app.settings

    data class InstalledApp(
        val packageName: String,
        val label: String,
        val isSystem: Boolean
    )

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    private val _loadingApps = MutableStateFlow(false)
    val loadingApps: StateFlow<Boolean> = _loadingApps.asStateFlow()

    /**
     * Lists apps that have a launcher entry. That is deliberately narrower than every
     * installed package: it avoids asking for the QUERY_ALL_PACKAGES permission, and
     * the apps a user wants to exempt are the ones they can actually open.
     */
    fun loadInstalledApps() {
        if (_loadingApps.value || _apps.value.isNotEmpty()) return
        viewModelScope.launch {
            _loadingApps.value = true
            try {
                _apps.value = withContext(Dispatchers.IO) {
                    val pm = app.packageManager
                    val launcherIntent = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                    pm.queryIntentActivities(launcherIntent, 0)
                        .asSequence()
                        .mapNotNull { it.activityInfo?.applicationInfo }
                        .distinctBy { it.packageName }
                        .filter { it.packageName != app.packageName }
                        .map { info: ApplicationInfo ->
                            InstalledApp(
                                packageName = info.packageName,
                                label = pm.getApplicationLabel(info).toString(),
                                isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                            )
                        }
                        .sortedBy { it.label.lowercase() }
                        .toList()
                }
            } catch (e: PackageManager.NameNotFoundException) {
                _apps.value = emptyList()
            } finally {
                _loadingApps.value = false
            }
        }
    }

    fun toggleBypass(packageName: String, bypass: Boolean) = viewModelScope.launch {
        val current = settings.bypassPackages.first().toMutableSet()
        if (bypass) current += packageName else current -= packageName
        settings.setBypassPackages(current)
    }

    fun setUpstreamDns(value: String) = viewModelScope.launch {
        settings.setUpstreamDns(value.trim())
    }

    fun setAutostart(value: Boolean) = viewModelScope.launch { settings.setShieldAutostart(value) }
    fun setDynamicColor(value: Boolean) = viewModelScope.launch { settings.setDynamicColor(value) }
    fun setSkipSilence(value: Boolean) = viewModelScope.launch { settings.setSkipSilence(value) }
    fun setPlaybackSpeed(value: Float) = viewModelScope.launch { settings.setPlaybackSpeed(value) }
    fun setSleepTimerMinutes(value: Long) = viewModelScope.launch {
        settings.setSleepTimerMinutes(value)
    }
}
