package com.oakcraft.lumen.blocker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live view of the tunnel, shared between the service and the UI.
 *
 * This lives in memory only. Totals that survive a restart are kept in settings and
 * folded in periodically, so the UI can show both "since boot" and "all time".
 */
object ShieldState {

    data class BlockedQuery(val host: String, val atMillis: Long)

    data class Status(
        val running: Boolean = false,
        val starting: Boolean = false,
        val rulesLoaded: Int = 0,
        val queriesThisSession: Long = 0,
        val blockedThisSession: Long = 0,
        val recentBlocks: List<BlockedQuery> = emptyList(),
        val lastError: String? = null
    ) {
        val blockRate: Float
            get() = if (queriesThisSession == 0L) 0f
            else blockedThisSession.toFloat() / queriesThisSession.toFloat()
    }

    private const val RECENT_LIMIT = 60

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun setStarting() = _status.update { it.copy(starting = true, lastError = null) }

    fun setRunning(rulesLoaded: Int) = _status.update {
        it.copy(
            running = true,
            starting = false,
            rulesLoaded = rulesLoaded,
            queriesThisSession = 0,
            blockedThisSession = 0,
            recentBlocks = emptyList(),
            lastError = null
        )
    }

    fun setStopped(error: String? = null) = _status.update {
        it.copy(running = false, starting = false, lastError = error)
    }

    fun recordAllowed() = _status.update {
        it.copy(queriesThisSession = it.queriesThisSession + 1)
    }

    fun recordBlocked(host: String) = _status.update { current ->
        val entry = BlockedQuery(host, System.currentTimeMillis())
        current.copy(
            queriesThisSession = current.queriesThisSession + 1,
            blockedThisSession = current.blockedThisSession + 1,
            recentBlocks = (listOf(entry) + current.recentBlocks).take(RECENT_LIMIT)
        )
    }
}
