package com.oakcraft.lumen.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oakcraft.lumen.BuildConfig
import com.oakcraft.lumen.core.LumenSettings
import com.oakcraft.lumen.ui.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings = viewModel.settings

    val autostart by settings.shieldAutostart.collectAsStateWithLifecycle(initialValue = false)
    val dynamicColor by settings.dynamicColor.collectAsStateWithLifecycle(initialValue = true)
    val skipSilence by settings.skipSilence.collectAsStateWithLifecycle(initialValue = false)
    val upstream by settings.upstreamDns.collectAsStateWithLifecycle(
        initialValue = LumenSettings.DEFAULT_UPSTREAM
    )
    val bypass by settings.bypassPackages.collectAsStateWithLifecycle(initialValue = emptySet())
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val loadingApps by viewModel.loadingApps.collectAsStateWithLifecycle()

    var upstreamDraft by remember(upstream) { mutableStateOf(upstream) }

    LaunchedEffect(Unit) { viewModel.loadInstalledApps() }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SettingsCard("Shield") {
                SwitchRow(
                    title = "Start after reboot",
                    subtitle = "Only works while the VPN permission stays granted.",
                    checked = autostart,
                    onCheckedChange = viewModel::setAutostart
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Upstream resolver", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Queries that are not blocked are forwarded here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = upstreamDraft,
                    onValueChange = { upstreamDraft = it },
                    label = { Text("IP address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.setUpstreamDns(upstreamDraft) },
                    enabled = upstreamDraft.isNotBlank() && upstreamDraft != upstream,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save resolver") }
            }
        }

        item {
            SettingsCard("Playback") {
                SwitchRow(
                    title = "Skip silence",
                    subtitle = "Trims long silent stretches. Handy for spoken word.",
                    checked = skipSilence,
                    onCheckedChange = viewModel::setSkipSilence
                )
            }
        }

        item {
            SettingsCard("Battery") {
                Text(
                    "The shield only wakes when an app looks up a name, and the player " +
                        "holds a wake lock solely while audio is actually playing. If Android " +
                        "is still stopping playback in the background, exempting Lumen from " +
                        "battery optimisation will fix it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                val ignoring = remember {
                    val pm = context.getSystemService(PowerManager::class.java)
                    pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                }
                OutlinedButton(
                    onClick = {
                        // Deliberately opens the settings list rather than the direct
                        // request dialog, which Play policy restricts to a narrow set of apps.
                        val intent = if (ignoring) {
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        } else {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (ignoring) "Battery optimisation is off" else "Open battery settings")
                }
            }
        }

        item {
            SettingsCard("Appearance") {
                SwitchRow(
                    title = "Match system colours",
                    subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        "Use the wallpaper palette."
                    } else {
                        "Your Android version uses Lumen's own palette."
                    },
                    checked = dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor
                )
            }
        }

        item {
            Text("Apps that bypass the shield", style = MaterialTheme.typography.titleMedium)
            Text(
                "Traffic from these apps skips the tunnel entirely. Useful for a banking " +
                    "app that refuses to run with a VPN active.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (loadingApps) {
            item { CircularProgressIndicator(Modifier.padding(16.dp)) }
        }

        items(apps, key = { it.packageName }) { installed ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        installed.label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        installed.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = installed.packageName in bypass,
                    onCheckedChange = { viewModel.toggleBypass(installed.packageName, it) }
                )
            }
        }

        item {
            Text(
                "Lumen ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
