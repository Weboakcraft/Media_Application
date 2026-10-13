package com.oakcraft.lumen.blocker

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.oakcraft.lumen.LumenApp
import com.oakcraft.lumen.R
import com.oakcraft.lumen.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Quick Settings toggle for the shield, so it can be turned on and off without opening
 * the app. If VPN consent has not been granted yet the tile hands off to the app, since
 * the consent dialog can only be raised from an activity.
 */
class ShieldTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = tileScope
        tileScope.launch {
            ShieldState.status.collectLatest { status ->
                qsTile?.apply {
                    state = when {
                        status.running -> Tile.STATE_ACTIVE
                        status.starting -> Tile.STATE_UNAVAILABLE
                        else -> Tile.STATE_INACTIVE
                    }
                    subtitle = if (status.running) {
                        getString(R.string.tile_blocked_count, status.blockedThisSession)
                    } else {
                        getString(R.string.tile_off)
                    }
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (ShieldState.status.value.running) {
            LumenVpnService.stop(this)
            (applicationContext as LumenApp).applicationScope.launch {
                (applicationContext as LumenApp).settings.setShieldEnabled(false)
            }
            return
        }

        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }
        LumenVpnService.start(this)
        (applicationContext as LumenApp).applicationScope.launch {
            (applicationContext as LumenApp).settings.setShieldEnabled(true)
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
