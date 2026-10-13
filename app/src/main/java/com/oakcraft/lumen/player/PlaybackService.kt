package com.oakcraft.lumen.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.oakcraft.lumen.LumenApp
import com.oakcraft.lumen.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The player that outlives the UI.
 *
 * Audio keeps going when the app is backgrounded, the screen goes off, or the task is
 * swiped away — that last one is [onTaskRemoved], which deliberately keeps the service
 * alive while something is actually playing.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var scope: CoroutineScope
    private var sleepTimerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        val player = buildPlayer()
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(sessionActivity)
            .build()

        scope.launch { applySavedPreferences(player) }
    }

    private fun buildPlayer(): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableAudioTrackPlaybackParams(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        return ExoPlayer.Builder(this, renderersFactory)
            // `true` here hands audio focus management to the player: it ducks for
            // navigation prompts and pauses for calls without any code of ours.
            .setAudioAttributes(audioAttributes, true)
            // Pause when headphones are unplugged rather than blaring out the speaker.
            .setHandleAudioBecomingNoisy(true)
            // Hold only the wake lock the current source needs. Local files take a
            // partial lock; network streams also keep wifi alive.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
    }

    private suspend fun applySavedPreferences(player: ExoPlayer) {
        val settings = (applicationContext as LumenApp).settings
        runCatching {
            player.setPlaybackSpeed(settings.playbackSpeed.first())
            player.skipSilenceEnabled = settings.skipSilence.first()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Keeps playback running after the task is swiped out of recents, and tears the
     * service down only when nothing is playing — which is what stops a silent service
     * from lingering and draining the battery.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        sleepTimerJob?.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_CANCEL_SLEEP_TIMER, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                COMMAND_SLEEP_TIMER -> {
                    val minutes = args.getLong(EXTRA_SLEEP_MINUTES, 30L)
                    startSleepTimer(minutes)
                }
                COMMAND_CANCEL_SLEEP_TIMER -> cancelSleepTimer()
                else -> return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun startSleepTimer(minutes: Long) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            SleepTimer.clear()
            return
        }

        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        SleepTimer.set(endsAt)

        sleepTimerJob = scope.launch {
            while (true) {
                val remaining = endsAt - System.currentTimeMillis()
                if (remaining <= 0) break
                SleepTimer.tick(remaining)
                delay(minOf(remaining, 1000L))
            }
            fadeOutAndPause()
            SleepTimer.clear()
        }
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        SleepTimer.clear()
    }

    /** A short fade is far less jarring than audio stopping dead mid-track. */
    private suspend fun fadeOutAndPause() {
        val player = mediaSession?.player ?: return
        val startVolume = player.volume
        val steps = 20
        repeat(steps) { step ->
            player.volume = startVolume * (1f - (step + 1).toFloat() / steps)
            delay(FADE_STEP_MS)
        }
        player.pause()
        player.volume = startVolume
    }

    companion object {
        const val COMMAND_SLEEP_TIMER = "com.oakcraft.lumen.SLEEP_TIMER"
        const val COMMAND_CANCEL_SLEEP_TIMER = "com.oakcraft.lumen.CANCEL_SLEEP_TIMER"
        const val EXTRA_SLEEP_MINUTES = "sleep_minutes"

        private const val SEEK_BACK_MS = 10_000L
        private const val SEEK_FORWARD_MS = 30_000L
        private const val FADE_STEP_MS = 150L
    }
}

/** Remaining sleep-timer time, so the UI can count down without owning the timer. */
object SleepTimer {
    private val _remainingMs = MutableStateFlow<Long?>(null)
    val remainingMs: StateFlow<Long?> = _remainingMs.asStateFlow()

    fun set(endsAtMillis: Long) {
        _remainingMs.value = (endsAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun tick(remaining: Long) {
        _remainingMs.value = remaining
    }

    fun clear() {
        _remainingMs.value = null
    }
}
