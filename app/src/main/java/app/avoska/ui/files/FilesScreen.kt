package app.avoska.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.avoska.R
import app.avoska.engine.Connection
import app.avoska.engine.Entry
import app.avoska.engine.EntryState
import app.avoska.engine.FileKind
import app.avoska.engine.SyncFailure
import app.avoska.ui.common.AppIcon
import app.avoska.ui.common.AccentOutlinedButton
import app.avoska.ui.common.AppTopBar
import app.avoska.ui.common.ThinProgress
import app.avoska.ui.common.formatProgress
import app.avoska.ui.common.formatSize
import app.avoska.ui.common.percent
import app.avoska.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

@Composable
fun FilesScreen(
    viewModel: FilesViewModel,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let {
        when (it) {
            is FilesMessage.Opened -> stringResource(R.string.message_opened, it.name)
            FilesMessage.DownloadUnavailable -> stringResource(R.string.message_download_unavailable)
        }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(message) {
        if (message != null) {
            // Cleared at once so it never repeats; shown in a scope the cleared state does not cancel.
            viewModel.messageShown()
            scope.launch { snackbar.showSnackbar(message) }
        }
    }
    // Leaving cancels a pending open; a configuration change (rotation, theme) does not.
    val activity = LocalActivity.current
    fun leftScreen() {
        if (activity?.isChangingConfigurations != true) viewModel.cancelPendingOpen()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { leftScreen() }
    DisposableEffect(viewModel) { onDispose { leftScreen() } }

    Scaffold(
        topBar = {
            Column {
                AppTopBar(
                    title = state.title,
                    onBack = onBack,
                    subtitle = connectionText(state.connection),
                    subtitleColor = if (state.connection is Connection.Online) {
                        LocalStatusColors.current.ok
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                painterResource(R.drawable.ic_gear),
                                contentDescription = stringResource(R.string.settings),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
                if (state.connection == Connection.Disabled) {
                    OfflineBanner(onTurnOff = viewModel::turnOffOfflineMode)
                }
                state.offers.forEach { (offer, deviceName) ->
                    OfferBanner(
                        text = stringResource(R.string.offer_banner, deviceName, offer.label),
                        onIgnore = { viewModel.ignoreOffer(offer) },
                        onAccept = { viewModel.acceptOffer(offer) },
                    )
                }
                Tabs(state, viewModel::selectTab)
                SortRow(state.sort, viewModel::selectSort)
            }
        },
        bottomBar = {
            SyncBottomBar(
                bar = state.bar,
                onSync = viewModel::sync,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onStop = viewModel::stop,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val rows = state.rows
        if (rows == null) {
            Spacer(Modifier.padding(padding))
        } else if (rows.isEmpty()) {
            Text(
                text = stringResource(if (state.tab == FilesTab.ON_DEVICE) R.string.empty_on_device else R.string.empty_folder),
                modifier = Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(rows, key = { it.entry.ref.path }) { row ->
                    FileRowItem(
                        row = row,
                        onClick = { if (row.entry.isFolder) onOpenFolder(row.entry.ref.path) else viewModel.openFile(row) },
                        onToggle = { viewModel.toggleKeep(row, it) },
                        onMenu = { viewModel.showMenu(row.entry.ref) },
                    )
                }
            }
        }
    }

    state.menu?.let { row ->
        FileMenuSheet(
            row = row,
            canDownload = state.canDownload,
            onAction = { viewModel.onMenuAction(row, it) },
            onDismiss = viewModel::dismissMenu,
        )
    }
    state.confirmRemove?.let { RemoveDialog(it, onConfirm = viewModel::confirmRemove, onDismiss = viewModel::dismissRemove) }
    state.details?.let { DetailsDialog(it, onDismiss = viewModel::dismissDetails) }
}

@Composable
private fun connectionText(status: Connection): String = when (status) {
    Connection.Connecting -> stringResource(R.string.status_connecting)
    is Connection.Online -> pluralStringResource(R.plurals.status_connected, status.total, status.connected, status.total)
    Connection.NoDevice -> stringResource(R.string.status_offline)
    Connection.Disabled -> stringResource(R.string.status_offline_mode)
}

@Composable
private fun OfflineBanner(onTurnOff: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Text(
                stringResource(R.string.offline_banner),
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onTurnOff) { Text(stringResource(R.string.turn_off), fontSize = 15.sp) }
        }
    }
}

@Composable
private fun OfferBanner(text: String, onIgnore: () -> Unit, onAccept: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Text(
                text,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onIgnore) { Text(stringResource(R.string.ignore), fontSize = 15.sp) }
            TextButton(onClick = onAccept) { Text(stringResource(R.string.accept), fontSize = 15.sp) }
        }
    }
}

@Composable
private fun Tabs(state: FilesUiState, onSelect: (FilesTab) -> Unit) {
    val selected = state.tab.ordinal
    PrimaryTabRow(
        selectedTabIndex = selected,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.primary,
        indicator = {
            TabRowDefaults.SecondaryIndicator(
                Modifier.tabIndicatorOffset(selected, matchContentSize = false),
                height = 3.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
    ) {
        val labels = listOf(
            stringResource(R.string.tab_all_files),
            state.onDeviceBytes?.let { stringResource(R.string.tab_on_device, formatSize(it)) }
                ?: stringResource(R.string.tab_on_device_plain),
        )
        FilesTab.entries.forEachIndexed { i, tab ->
            Tab(
                selected = i == selected,
                onClick = { onSelect(tab) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = { Text(labels[i], fontSize = 14.sp, fontWeight = FontWeight.Medium) },
            )
        }
    }
}

@Composable
private fun SortRow(sort: SortOrder, onSelect: (SortOrder) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Text(stringResource(R.string.sort_by), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val labels = mapOf(
            SortOrder.NAME to R.string.sort_name,
            SortOrder.SIZE to R.string.sort_size,
            SortOrder.DATE to R.string.sort_date,
        )
        SortOrder.entries.forEach { order ->
            val selected = order == sort
            FilterChip(
                selected = selected,
                onClick = { onSelect(order) },
                label = { Text(stringResource(labels.getValue(order)), fontSize = 14.sp) },
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = MaterialTheme.colorScheme.outline,
                    selectedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun FileRowItem(row: FileRow, onClick: () -> Unit, onToggle: (Boolean) -> Unit, onMenu: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp),
    ) {
        val entry = row.entry
        AppIcon(rowIcon(entry))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, lineHeight = 22.sp)
            val state = entry.state
            if (state is EntryState.Downloading) {
                ThinProgress(
                    progress = state.doneBytes.toFloat() / state.totalBytes.coerceAtLeast(1),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp).heightIn(max = 4.dp),
                )
                Text(
                    formatProgress(state.doneBytes, state.totalBytes, stringResource(R.string.of)),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(secondLine(row), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        val keepLabel = stringResource(R.string.keep_on_device)
        Switch(
            checked = entry.keep,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics { contentDescription = keepLabel },
        )
        IconButton(onClick = onMenu) {
            Icon(
                painterResource(R.drawable.ic_more),
                contentDescription = stringResource(R.string.more_actions, entry.name),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(4.dp))
    }
}

fun rowIcon(entry: Entry): Int = when (entry.kind) {
    FileKind.FOLDER -> R.drawable.ic_folder
    FileKind.VIDEO -> R.drawable.ic_video
    FileKind.DOCUMENT -> R.drawable.ic_doc
}

@Composable
private fun secondLine(row: FileRow) = buildAnnotatedString {
    val entry = row.entry
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val status = LocalStatusColors.current
    val sep = stringResource(R.string.separator)
    withStyle(SpanStyle(color = dim)) {
        append(formatSize(entry.sizeBytes))
        if (row.location != null) {
            append(sep)
            append(row.location)
        }
    }
    // On the On device tab the location replaces the obvious "On device".
    val onDeviceTab = row.location != null
    val (text, color) = when (val state = entry.state) {
        EntryState.NotSelected -> null to dim
        EntryState.SomeSelected -> stringResource(R.string.state_some_selected) to dim
        is EntryState.Waiting -> stringResource(
            if (state.queued) R.string.state_waiting else R.string.state_waiting_for_sync,
        ) to MaterialTheme.colorScheme.primary
        EntryState.OnDevice -> (if (onDeviceTab) null else stringResource(R.string.state_on_device)) to status.ok
        EntryState.Outdated -> stringResource(
            if (onDeviceTab) R.string.state_outdated_short else R.string.state_outdated,
        ) to status.warn
        is EntryState.Downloading -> null to dim
    }
    if (text != null) {
        withStyle(SpanStyle(color = dim)) { append(sep) }
        withStyle(SpanStyle(color = color)) { append(text) }
    }
}

@Composable
private fun SyncBottomBar(
    bar: SyncBar,
    onSync: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        ) {
            when (bar) {
                is SyncBar.Idle -> IdleBar(bar, onSync)
                is SyncBar.Running -> RunningBar(bar, onPause, onResume, onStop)
            }
        }
    }
}

@Composable
private fun IdleBar(bar: SyncBar.Idle, onSync: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (bar.pendingBytes > 0) {
                    stringResource(R.string.to_download, formatSize(bar.pendingBytes))
                } else {
                    stringResource(R.string.nothing_to_download)
                },
                fontSize = 16.sp,
            )
            val hint = when (bar.block) {
                SyncBlock.NOT_ENOUGH_SPACE ->
                    stringResource(R.string.block_not_enough_space, formatSize(bar.pendingBytes - bar.freeBytes))
                SyncBlock.CONNECTING, SyncBlock.NOTHING_TO_DOWNLOAD, null -> when (bar.failure) {
                    SyncFailure.CONNECTION_LOST -> stringResource(R.string.failure_connection_lost)
                    SyncFailure.OUT_OF_SPACE -> stringResource(R.string.failure_out_of_space)
                    null -> stringResource(R.string.free_on_device, formatSize(bar.freeBytes))
                }
                else -> blockText(bar.block)
            }
            Text(hint, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(
            onClick = onSync,
            enabled = bar.block == null,
            modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 24.dp),
        ) { Text(stringResource(R.string.sync), fontSize = 15.sp) }
    }
}

@Composable
private fun RunningBar(bar: SyncBar.Running, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (bar.paused) {
                    stringResource(R.string.sync_paused)
                } else {
                    stringResource(R.string.syncing_percent, percent(bar.doneBytes, bar.totalBytes))
                },
                fontSize = 16.sp,
            )
            Text(
                bar.resumeBlock?.let { blockText(it) } ?: stringResource(
                    R.string.sync_file_progress,
                    formatProgress(bar.doneBytes, bar.totalBytes, stringResource(R.string.of)),
                    bar.fileNumber,
                    bar.fileCount,
                ),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AccentOutlinedButton(
            onClick = if (bar.paused) onResume else onPause,
            enabled = bar.resumeBlock == null,
            modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 18.dp),
        ) { Text(stringResource(if (bar.paused) R.string.resume else R.string.pause), fontSize = 15.sp) }
        Spacer(Modifier.width(8.dp))
        AccentOutlinedButton(
            onClick = onStop,
            modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 18.dp),
        ) { Text(stringResource(R.string.stop), fontSize = 15.sp) }
    }
    ThinProgress(
        progress = bar.doneBytes.toFloat() / bar.totalBytes.coerceAtLeast(1),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
}

@Composable
private fun blockText(block: SyncBlock): String = when (block) {
    SyncBlock.OFFLINE_MODE -> stringResource(R.string.block_offline_mode)
    SyncBlock.NO_DEVICE -> stringResource(R.string.block_no_device)
    SyncBlock.NETWORK_OFF -> stringResource(R.string.block_network_off)
    SyncBlock.CONNECTING -> stringResource(R.string.status_connecting)
    SyncBlock.NOTHING_TO_DOWNLOAD -> stringResource(R.string.nothing_to_download)
    SyncBlock.NOT_ENOUGH_SPACE -> stringResource(R.string.block_not_enough_space_short)
}
