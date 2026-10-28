package dev.smartdns.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.smartdns.R
import dev.smartdns.core.ConnectionState
import dev.smartdns.core.Desired
import dev.smartdns.core.EnforceError
import dev.smartdns.core.OtherTransport
import dev.smartdns.core.PrivateDnsMode
import dev.smartdns.core.PrivateDnsState
import dev.smartdns.core.RuleProblem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun MonitorRoute(viewModel: MonitorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val probe by viewModel.probeState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onUiStarted() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onUiStopped() }

    MonitorScreen(
        state = state,
        probe = probe,
        onSsidChange = viewModel::onSsidDraftChange,
        onHostChange = viewModel::onHostDraftChange,
        onSaveRule = viewModel::saveRule,
        onProbeDot = viewModel::probeDot,
        onToggleMonitoring = viewModel::setMonitoring,
        onApplyNow = viewModel::applyNow,
        onRestoreAutomatic = viewModel::restoreAutomatic,
        onRequestPermissions = {
            val needed = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            permissionLauncher.launch(needed.toTypedArray())
        },
        onOpenLocationSettings = {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        },
        onOpenAppSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${context.packageName}".toUri(),
                ),
            )
        },
        onRequestBatteryExemption = {
            // The app-list screen, not the direct per-app request: the latter is a Play policy
            // violation, and the exemption only softens Doze for an app that already holds a
            // foreground service.
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        },
        onCopyAdb = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("adb", state.adbCommand))
            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        },
    )
}

@Composable
private fun MonitorScreen(
    state: MonitorUiState,
    probe: ProbeState,
    onSsidChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onSaveRule: () -> Unit,
    onProbeDot: () -> Unit,
    onToggleMonitoring: (Boolean) -> Unit,
    onApplyNow: () -> Unit,
    onRestoreAutomatic: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onCopyAdb: () -> Unit,
) {
    Scaffold { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusCard(state)
            RuleCard(state, probe, onSsidChange, onHostChange, onSaveRule, onProbeDot)
            PermissionCard(
                state = state,
                onRequestPermissions = onRequestPermissions,
                onOpenLocationSettings = onOpenLocationSettings,
                onOpenAppSettings = onOpenAppSettings,
                onRequestBatteryExemption = onRequestBatteryExemption,
                onCopyAdb = onCopyAdb,
            )
            ControlCard(state, onToggleMonitoring, onApplyNow, onRestoreAutomatic)
        }
    }
}

@Composable
private fun StatusCard(state: MonitorUiState) {
    val statusText = when {
        state.enabled && state.serviceRunning -> stringResource(R.string.status_running)
        state.enabled -> stringResource(R.string.status_enabled_not_running)
        else -> stringResource(R.string.status_disabled)
    }
    val statusColor = when {
        state.enabled && state.serviceRunning -> MaterialTheme.colorScheme.primary
        state.enabled -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val ssidUnreadable = when (val connection = state.connection) {
        is ConnectionState.Wifi -> connection.ssid == null
        else -> false
    }

    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            InfoRow(stringResource(R.string.label_network), networkText(state.connection))
            InfoRow(stringResource(R.string.label_decision), decisionText(state.desired))
            InfoRow(stringResource(R.string.label_system), systemText(state.systemState))
            InfoRow(stringResource(R.string.label_last_check), lastCheckText(state))

            if (!state.writeGranted) {
                Notice(stringResource(R.string.warn_no_write), isError = true)
            }
            if (state.enabled && !state.backgroundLocationGranted) {
                Notice(stringResource(R.string.warn_background_location), isError = true)
            } else if (state.enabled && ssidUnreadable) {
                Notice(stringResource(R.string.warn_ssid_unreadable), isError = false)
            }
            if (state.enabled && state.savedRule != null && !state.serviceRunning) {
                Notice(stringResource(R.string.warn_service_not_running), isError = true)
            }
        }
    }
}

@Composable
private fun RuleCard(
    state: MonitorUiState,
    probe: ProbeState,
    onSsidChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onSaveRule: () -> Unit,
    onProbeDot: () -> Unit,
) {
    val ssidProblem = state.ssidProblem
    val hostProblem = state.hostProblem

    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.rule_title))
            OutlinedTextField(
                value = state.ssidDraft,
                onValueChange = onSsidChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ssid_label)) },
                placeholder = { Text(stringResource(R.string.ssid_hint)) },
                singleLine = true,
                isError = ssidProblem != null,
                supportingText = ssidProblem?.let { problem -> { Text(problemText(problem)) } },
            )
            OutlinedTextField(
                value = state.hostDraft,
                onValueChange = onHostChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.dot_label)) },
                placeholder = { Text(stringResource(R.string.dot_hint)) },
                singleLine = true,
                isError = hostProblem != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = hostProblem?.let { problem -> { Text(problemText(problem)) } },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSaveRule, enabled = state.draftRule != null) {
                    Text(stringResource(R.string.save_rule))
                }
                OutlinedButton(
                    onClick = onProbeDot,
                    enabled = hostProblem == null && state.hostDraft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.probe_dot))
                }
            }
            Text(
                text = probeText(probe),
                style = MaterialTheme.typography.bodySmall,
                color = if (probe is ProbeState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = stringResource(
                    if (state.savedRule == null) R.string.rule_empty_hint else R.string.rule_saved_hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionCard(
    state: MonitorUiState,
    onRequestPermissions: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onCopyAdb: () -> Unit,
) {
    val granted = stringResource(R.string.perm_granted)
    val missing = stringResource(R.string.perm_missing)
    val enabled = stringResource(R.string.perm_enabled)
    val disabled = stringResource(R.string.perm_disabled)
    val exempt = stringResource(R.string.perm_battery_exempt)
    val optimized = stringResource(R.string.perm_battery_optimized)

    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.perm_title))
            PermissionRow(stringResource(R.string.perm_write_secure), if (state.writeGranted) granted else missing, state.writeGranted)
            PermissionRow(stringResource(R.string.perm_location), if (state.locationGranted) granted else missing, state.locationGranted)
            PermissionRow(
                stringResource(R.string.perm_background_location),
                if (state.backgroundLocationGranted) granted else missing,
                state.backgroundLocationGranted,
            )
            PermissionRow(stringResource(R.string.perm_location_service), if (state.locationEnabled) enabled else disabled, state.locationEnabled)
            PermissionRow(stringResource(R.string.perm_notifications), if (state.notificationsEnabled) enabled else disabled, state.notificationsEnabled)
            PermissionRow(
                label = stringResource(R.string.perm_battery),
                value = if (state.batteryExempt) exempt else optimized,
                ok = state.batteryExempt,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.adb_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(modifier = Modifier.padding(start = 10.dp, top = 6.dp, end = 4.dp, bottom = 2.dp)) {
                    Text(
                        text = state.adbCommand,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    TextButton(onClick = onCopyAdb, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.copy))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.request_runtime), textAlign = TextAlign.Center)
                }
                if (!state.locationEnabled) {
                    OutlinedButton(onClick = onOpenLocationSettings, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.open_location_settings), textAlign = TextAlign.Center)
                    }
                }
            }
            if (!state.backgroundLocationGranted) {
                OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.open_app_settings))
                }
            }
            if (!state.batteryExempt) {
                OutlinedButton(onClick = onRequestBatteryExemption, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.request_battery))
                }
            }
            Text(
                text = stringResource(R.string.battery_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.notification_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.warn_oem),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ControlCard(
    state: MonitorUiState,
    onToggleMonitoring: (Boolean) -> Unit,
    onApplyNow: () -> Unit,
    onRestoreAutomatic: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(stringResource(R.string.action_title))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.monitor_switch),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.savedRule == null) {
                        Text(
                            text = stringResource(R.string.monitor_needs_rule),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = onToggleMonitoring,
                    enabled = state.savedRule != null,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApplyNow, enabled = state.enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.apply_now))
                }
                OutlinedButton(onClick = onRestoreAutomatic, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.restore_auto))
                }
            }
            Text(
                text = stringResource(R.string.restore_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.58f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    value: String,
    ok: Boolean,
    color: Color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
private fun Notice(text: String, isError: Boolean) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun networkText(connection: ConnectionState): String = when (connection) {
    ConnectionState.Offline -> stringResource(R.string.network_offline)
    is ConnectionState.Wifi ->
        if (connection.ssid == null) {
            stringResource(R.string.network_wifi_unknown)
        } else {
            stringResource(R.string.network_wifi, connection.ssid)
        }
    is ConnectionState.Other -> when (connection.transport) {
        OtherTransport.CELLULAR -> stringResource(R.string.network_cellular)
        OtherTransport.ETHERNET -> stringResource(R.string.network_ethernet)
        OtherTransport.VPN -> stringResource(R.string.network_vpn)
        OtherTransport.OTHER -> stringResource(R.string.network_other)
    }
}

@Composable
private fun decisionText(desired: Desired): String = when (desired) {
    Desired.Keep -> stringResource(R.string.decision_keep)
    is Desired.Apply -> when (desired.target.mode) {
        PrivateDnsMode.OPPORTUNISTIC -> stringResource(R.string.decision_automatic)
        PrivateDnsMode.HOSTNAME -> stringResource(R.string.decision_host, desired.target.specifier)
        PrivateDnsMode.OFF -> stringResource(R.string.decision_keep)
    }
}

@Composable
private fun systemText(state: PrivateDnsState): String = when (PrivateDnsMode.fromValue(state.mode)) {
    null -> stringResource(R.string.system_not_set)
    PrivateDnsMode.OFF -> stringResource(R.string.mode_off)
    PrivateDnsMode.OPPORTUNISTIC -> stringResource(R.string.mode_opportunistic)
    PrivateDnsMode.HOSTNAME -> {
        val specifier = state.specifier.orEmpty()
        if (specifier.isBlank()) {
            stringResource(R.string.mode_hostname)
        } else {
            stringResource(
                R.string.system_value,
                stringResource(R.string.mode_hostname),
                specifier,
            )
        }
    }
}

@Composable
private fun lastCheckText(state: MonitorUiState): String {
    if (state.lastCheckAt <= 0L) return stringResource(R.string.last_none)
    val clock = remember(state.lastCheckAt) {
        Instant.ofEpochMilli(state.lastCheckAt)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(ClockFormat)
    }
    return when {
        state.lastError == EnforceError.WRITE_DENIED -> stringResource(R.string.last_denied, clock)
        state.lastError == EnforceError.WRITE_NOT_APPLIED -> stringResource(R.string.last_not_applied, clock)
        state.lastChanged -> stringResource(R.string.last_synced, clock)
        state.lastTarget == null -> stringResource(R.string.last_kept, clock)
        else -> stringResource(R.string.last_unchanged, clock)
    }
}

@Composable
private fun probeText(probe: ProbeState): String = when (probe) {
    ProbeState.Idle -> stringResource(R.string.probe_idle)
    ProbeState.Invalid -> stringResource(R.string.probe_invalid)
    ProbeState.Running -> stringResource(R.string.probe_running)
    is ProbeState.Ok -> stringResource(R.string.probe_ok, probe.millis)
    is ProbeState.Failed -> stringResource(R.string.probe_failed, probe.reason)
}

@Composable
private fun problemText(problem: RuleProblem): String = when (problem) {
    RuleProblem.SSID_EMPTY -> stringResource(R.string.problem_ssid_empty)
    RuleProblem.SSID_TOO_LONG -> stringResource(R.string.problem_ssid_long)
    RuleProblem.HOST_EMPTY -> stringResource(R.string.problem_host_empty)
    RuleProblem.HOST_IS_IP -> stringResource(R.string.problem_host_ip)
    RuleProblem.HOST_TOO_LONG -> stringResource(R.string.problem_host_long)
    RuleProblem.HOST_LABEL_INVALID -> stringResource(R.string.problem_host_label)
    RuleProblem.HOST_LABEL_TOO_LONG -> stringResource(R.string.problem_host_label_long)
    RuleProblem.HOST_NEEDS_DOT -> stringResource(R.string.problem_host_need_dot)
}
