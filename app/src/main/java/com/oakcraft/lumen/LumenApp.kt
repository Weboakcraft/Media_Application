package com.oakcraft.lumen

import android.app.Application
import com.oakcraft.lumen.blocker.BlocklistRepository
import com.oakcraft.lumen.core.LumenSettings
import com.oakcraft.lumen.player.MediaLibrary
import com.oakcraft.lumen.player.PodcastRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Holds the few long-lived objects the app needs.
 *
 * There is no dependency-injection framework here on purpose: the graph is small enough
 * that a handful of lazies is clearer, and it keeps the APK and the build free of an
 * annotation processor.
 */
class LumenApp : Application() {

    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    val settings: LumenSettings by lazy { LumenSettings(this) }
    val blocklistRepository: BlocklistRepository by lazy { BlocklistRepository(this) }
    val mediaLibrary: MediaLibrary by lazy { MediaLibrary(this) }
    val podcastRepository: PodcastRepository by lazy { PodcastRepository(this) }
}
