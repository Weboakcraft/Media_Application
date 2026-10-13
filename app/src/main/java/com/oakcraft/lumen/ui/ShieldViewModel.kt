package com.oakcraft.lumen.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oakcraft.lumen.LumenApp
import com.oakcraft.lumen.blocker.ShieldState
import com.oakcraft.lumen.core.BlocklistSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShieldViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LumenApp
    private val settings = app.settings

    data class ListsState(
        val activeSourceIds: Set<String> = BlocklistSource.DEFAULT_IDS,
        val lastUpdate: Long = 0,
        val blockedTotal: Long = 0,
        val queriesTotal: Long = 0,
        val allowed: Set<String> = emptySet(),
        val denied: Set<String> = emptySet()
    )

    val status: StateFlow<ShieldState.Status> = ShieldState.status

    val lists: StateFlow<ListsState> = combine(
        settings.activeSources,
        settings.lastListUpdate,
        settings.blockedTotal,
        settings.queriesTotal,
        combine(settings.userAllow, settings.userDeny) { allow, deny -> allow to deny }
    ) { sources, updated, blocked, queries, rules ->
        ListsState(sources, updated, blocked, queries, rules.first, rules.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListsState())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val sources: List<BlocklistSource> = BlocklistSource.ALL

    fun refreshLists() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                val selected = settings.activeSources.first()
                if (selected.isEmpty()) {
                    _message.value = "Select at least one blocklist first."
                    return@launch
                }
                val result = app.blocklistRepository.refresh(selected)
                settings.setLastListUpdate(System.currentTimeMillis())
                _message.value = when {
                    result.domains == 0 ->
                        "No domains could be downloaded. Check your connection and try again."
                    result.failedSources.isEmpty() ->
                        "Loaded ${"%,d".format(result.domains)} domains."
                    else ->
                        "Loaded ${"%,d".format(result.domains)} domains. " +
                            "These could not be reached: ${result.failedSources.joinToString()}."
                }
            } catch (e: Exception) {
                _message.value = e.message ?: "The update failed."
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun toggleSource(id: String, enabled: Boolean) = viewModelScope.launch {
        val current = settings.activeSources.first().toMutableSet()
        if (enabled) current += id else current -= id
        settings.setActiveSources(current)
    }

    fun setShieldEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setShieldEnabled(enabled)
    }

    fun addRule(domain: String, allow: Boolean) = viewModelScope.launch {
        val clean = domain.trim().lowercase().removePrefix("www.")
        if (clean.isEmpty() || !clean.contains('.')) {
            _message.value = "Enter a domain, for example ads.example.com"
            return@launch
        }
        if (allow) {
            settings.setUserAllow(settings.userAllow.first() + clean)
        } else {
            settings.setUserDeny(settings.userDeny.first() + clean)
        }
        _message.value = if (allow) "$clean will always resolve." else "$clean will be blocked."
    }

    fun removeRule(domain: String, allow: Boolean) = viewModelScope.launch {
        if (allow) {
            settings.setUserAllow(settings.userAllow.first() - domain)
        } else {
            settings.setUserDeny(settings.userDeny.first() - domain)
        }
    }

    fun resetCounters() = viewModelScope.launch { settings.resetCounters() }

    fun consumeMessage() {
        _message.value = null
    }
}
