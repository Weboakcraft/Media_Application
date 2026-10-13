package com.oakcraft.lumen.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.oakcraft.lumen.LumenApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Brings the shield back after a reboot or an app update, but only if the user asked
 * for that and the VPN consent is still in place. Consent cannot be requested from here,
 * so if it has lapsed the shield simply stays off until the app is opened.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val app = context.applicationContext as? LumenApp ?: return
        val pendingResult = goAsync()

        app.applicationScope.launch {
            try {
                val wanted = app.settings.shieldAutostart.first() && app.settings.shieldEnabled.first()
                if (!wanted) return@launch

                val needsConsent = withContext(Dispatchers.Main) { VpnService.prepare(context) != null }
                if (needsConsent) {
                    Log.i(TAG, "Autostart skipped: VPN consent is no longer granted.")
                    return@launch
                }
                LumenVpnService.start(context)
            } catch (e: Exception) {
                Log.w(TAG, "Autostart failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
