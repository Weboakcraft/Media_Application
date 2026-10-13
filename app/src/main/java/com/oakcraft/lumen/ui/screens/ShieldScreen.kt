package com.oakcraft.lumen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oakcraft.lumen.blocker.ShieldState
import com.oakcraft.lumen.ui.ShieldViewModel
import com.oakcraft.lumen.ui.theme.StatNumberStyle
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ShieldScreen(
    viewModel: ShieldViewModel,
    onRequestEnable: () -> Unit,
    onDisable: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    var newRule by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { ShieldToggleCard(status, onRequestEnable, onDisable) }

        item {
            StatsRow(
                blockedSession = status.blockedThisSession,
                blockRate = status.blockRate,
                rulesLoaded = status.rulesLoaded,
                blockedTotal = lists.blockedTotal
            )
        }

        item {
            BlocklistsCard(
                viewModel = viewModel,
                activeIds = lists.activeSourceIds,
                lastUpdate = lists.lastUpdate,
                refreshing = refreshing
            )
        }

        item {
            CustomRulesCard(
                value = newRule,
                onValueChange = { newRule = it },
                allowed = lists.allowed,
                denied = lists.denied,
                onAdd = { allow ->
                    viewModel.addRule(newRule, allow)
                    newRule = ""
                },
                onRemove = viewModel::removeRule
            )
        }

        if (status.recentBlocks.isNotEmpty()) {
            item {
                Text(
                    text = "Recently blocked",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(status.recentBlocks, key = { it.host + it.atMillis }) { entry ->
                RecentBlockRow(entry)
            }
        }
    }
}

@Composable
private fun ShieldToggleCard(
    status: ShieldState.Status,
    onRequestEnable: () -> Unit,
    onDisable: () -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (status.running) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        status.starting -> "Starting…"
                        status.running -> "Shield is on"
                        else -> "Shield is off"
                    },
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = status.lastError
                        ?: if (status.running) {
                            "Filtering DNS for every app on this device."
                        } else {
                            "Turn on to block ad and tracker domains device-wide."
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.lastError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.size(12.dp))
            if (status.starting) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            } else {
                Switch(
                    checked = status.running,
                    onCheckedChange = { wanted -> if (wanted) onRequestEnable() else onDisable() }
                )
            }
        }
    }
}

@Composable
private fun StatsRow(
    blockedSession: Long,
    blockRate: Float,
    rulesLoaded: Int,
    blockedTotal: Long
) {
    // A LazyColumn item is a single slot, so the two rows need an explicit Column.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Blocked now", "%,d".format(blockedSession), Modifier.weight(1f))
            StatCard("Block rate", "${(blockRate * 100).toInt()}%", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Rules", "%,d".format(rulesLoaded), Modifier.weight(1f))
            StatCard("Blocked all time", "%,d".format(blockedTotal), Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(text = value, style = StatNumberStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BlocklistsCard(
    viewModel: ShieldViewModel,
    activeIds: Set<String>,
    lastUpdate: Long,
    refreshing: Boolean
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Blocklists", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (lastUpdate == 0L) {
                    "Not downloaded yet — update to start blocking."
                } else {
                    "Updated " + DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.SHORT,
                        Locale.getDefault()
                    ).format(Date(lastUpdate))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            viewModel.sources.forEach { source ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            source.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = source.id in activeIds,
                        onCheckedChange = { viewModel.toggleSource(source.id, it) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = viewModel::refreshLists,
                enabled = !refreshing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Updating…")
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Update blocklists")
                }
            }
            Text(
                text = "Changes take effect the next time the shield is started.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun CustomRulesCard(
    value: String,
    onValueChange: (String) -> Unit,
    allowed: Set<String>,
    denied: Set<String>,
    onAdd: (allow: Boolean) -> Unit,
    onRemove: (domain: String, allow: Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Your own rules", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "An allow rule always wins, which is how you fix a site a blocklist breaks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Domain") },
                placeholder = { Text("ads.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onAdd(false) }) {
                    Icon(Icons.Rounded.Block, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Block")
                }
                TextButton(onClick = { onAdd(true) }) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Always allow")
                }
            }

            if (denied.isNotEmpty() || allowed.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            RuleChips(denied, allow = false, onRemove = onRemove)
            RuleChips(allowed, allow = true, onRemove = onRemove)
        }
    }
}

@Composable
private fun RuleChips(
    domains: Set<String>,
    allow: Boolean,
    onRemove: (String, Boolean) -> Unit
) {
    domains.sorted().forEach { domain ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = if (allow) Icons.Rounded.Check else Icons.Rounded.Block,
                contentDescription = null,
                tint = if (allow) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = domain,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onRemove(domain, allow) }) {
                Icon(Icons.Rounded.Close, contentDescription = "Remove $domain")
            }
        }
    }
}

@Composable
private fun RecentBlockRow(entry: ShieldState.BlockedQuery) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier.size(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Block,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(8.dp)
            )
        }
        Spacer(Modifier.size(12.dp))
        Text(
            text = entry.host,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
                .format(Date(entry.atMillis)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
