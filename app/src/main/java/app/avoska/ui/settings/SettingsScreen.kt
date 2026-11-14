package app.avoska.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.avoska.BuildConfig
import app.avoska.R
import app.avoska.SyncNetwork
import app.avoska.ui.common.AppIcon
import app.avoska.ui.common.AppTopBar
import app.avoska.ui.common.formatSize
import app.avoska.ui.theme.LocalStatusColors

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onAddDevice: () -> Unit,
    onSyncNetwork: () -> Unit,
    onShowDeviceId: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAbout by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.settings), onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(bottom = 16.dp),
        ) {
            SectionHeader(stringResource(R.string.section_devices))
            state.devices.forEach { device ->
                SettingsRow(
                    icon = R.drawable.ic_pc,
                    title = device.name ?: device.id.substringBefore('-'),
                    value = stringResource(if (device.connected) R.string.connected else R.string.offline),
                    valueColor = if (device.connected) LocalStatusColors.current.ok else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsRow(
                icon = R.drawable.ic_plus,
                title = stringResource(R.string.add_device),
                titleColor = MaterialTheme.colorScheme.primary,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = onAddDevice,
            )
            SectionHeader(stringResource(R.string.section_folders))
            state.folders.forEach { folder ->
                SettingsRow(
                    icon = R.drawable.ic_folder,
                    title = folder.label,
                    value = pluralStringResource(R.plurals.folder_devices, folder.deviceCount, folder.deviceCount),
                )
            }
            SectionHeader(stringResource(R.string.section_sync))
            SettingsRow(
                title = stringResource(R.string.sync_network),
                value = stringResource(networkTitle(state.syncNetwork)),
                chevron = true,
                onClick = onSyncNetwork,
            )
            SettingsRow(
                title = stringResource(R.string.offline_mode),
                hint = stringResource(R.string.offline_mode_hint),
                onClick = { viewModel.setOfflineMode(!state.offlineMode) },
                trailing = { Switch(checked = state.offlineMode, onCheckedChange = viewModel::setOfflineMode) },
            )
            SectionHeader(stringResource(R.string.section_this_phone))
            SettingsRow(title = stringResource(R.string.show_my_device_id), chevron = true, onClick = onShowDeviceId)
            SettingsRow(
                title = stringResource(R.string.storage),
                value = stringResource(R.string.storage_value, formatSize(state.usedBytes), formatSize(state.freeBytes)),
            )
            SettingsRow(title = stringResource(R.string.about), chevron = true, onClick = { showAbout = true })
        }
    }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.about)) },
            text = { Text(stringResource(R.string.about_text, BuildConfig.VERSION_NAME)) },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

fun networkTitle(network: SyncNetwork): Int = when (network) {
    SyncNetwork.OFF -> R.string.network_off
    SyncNetwork.WIFI -> R.string.network_wifi
    SyncNetwork.WIFI_AND_MOBILE -> R.string.network_wifi_mobile
    SyncNetwork.ANY -> R.string.network_any
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRow(
    title: String,
    icon: Int? = null,
    hint: String? = null,
    value: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        if (icon != null) {
            AppIcon(icon, tint = iconTint)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, lineHeight = 22.sp, color = titleColor)
            if (hint != null) {
                Text(hint, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (value != null) Text(value, fontSize = 13.sp, color = valueColor)
        if (chevron) {
            Spacer(Modifier.width(12.dp))
            AppIcon(R.drawable.ic_next, tint = MaterialTheme.colorScheme.onSurface)
        }
        trailing?.invoke()
    }
}
