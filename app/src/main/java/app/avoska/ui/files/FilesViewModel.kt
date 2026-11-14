package app.avoska.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.avoska.AppSettings
import app.avoska.Preferences
import app.avoska.SyncNetwork
import app.avoska.engine.Connection
import app.avoska.engine.Entry
import app.avoska.engine.EngineStatus
import app.avoska.engine.EntryState
import app.avoska.engine.FileRef
import app.avoska.engine.FolderOffer
import app.avoska.engine.SyncEngine
import app.avoska.engine.SyncFailure
import app.avoska.engine.SyncKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why Sync, or Resume, is disabled. */
enum class SyncBlock { CONNECTING, OFFLINE_MODE, NO_DEVICE, NETWORK_OFF, NOTHING_TO_DOWNLOAD, NOT_ENOUGH_SPACE }

sealed interface SyncBar {
    data class Idle(val pendingBytes: Long, val freeBytes: Long, val block: SyncBlock?, val failure: SyncFailure?) : SyncBar
    data class Running(
        val doneBytes: Long,
        val totalBytes: Long,
        val fileNumber: Int,
        val fileCount: Int,
        val paused: Boolean,
        /** Set when paused and Resume cannot run now. */
        val resumeBlock: SyncBlock?,
    ) : SyncBar
}

sealed interface FilesMessage {
    data class Opened(val name: String) : FilesMessage
    data object DownloadUnavailable : FilesMessage
}

data class FilesUiState(
    val title: String,
    val connection: Connection,
    val tab: FilesTab = FilesTab.ALL,
    val sort: SortOrder = SortOrder.NAME,
    /** Null until the first file list arrives, so "not loaded" never looks like "empty". */
    val rows: List<FileRow>? = null,
    val onDeviceBytes: Long? = null,
    val bar: SyncBar,
    val canDownload: Boolean,
    /** Flow 2: folders offered later by any device, with the offering device's name. */
    val offers: List<Pair<FolderOffer, String>> = emptyList(),
    val menu: FileRow? = null,
    val confirmRemove: FileRow? = null,
    val details: FileRow? = null,
    val message: FilesMessage? = null,
)

private data class LocalUi(
    val tab: FilesTab = FilesTab.ALL,
    val sort: SortOrder = SortOrder.NAME,
    val menu: FileRef? = null,
    val remove: FileRef? = null,
    val details: FileRef? = null,
    val message: FilesMessage? = null,
)

/** Files at [dir] ("" is the root) of the shared folder [folderId]. */
class FilesViewModel(
    private val engine: SyncEngine,
    private val settings: AppSettings,
    private val folderId: String,
    private val dir: String,
) : ViewModel() {
    private val local = MutableStateFlow(LocalUi())
    private var pendingOpen: Job? = null

    private val onDeviceHere = engine.onDevice(folderId).map { list ->
        if (dir.isEmpty()) list else list.filter { it.ref.path.startsWith("$dir/") }
    }

    val uiState: StateFlow<FilesUiState> =
        combine(engine.status, settings.preferences, engine.entries(folderId, dir), onDeviceHere, local, ::build)
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                FilesUiState(
                    title = if (dir.isEmpty()) {
                        engine.status.value.folders.firstOrNull { it.id == folderId }?.label.orEmpty()
                    } else {
                        dir.substringAfterLast('/')
                    },
                    connection = engine.status.value.connection,
                    bar = syncBar(engine.status.value, settings.preferences.value),
                    canDownload = engine.status.value.canDownload,
                ),
            )

    fun selectTab(tab: FilesTab) = local.update { it.copy(tab = tab) }
    fun selectSort(sort: SortOrder) = local.update { it.copy(sort = sort) }

    fun toggleKeep(row: FileRow, keep: Boolean) {
        when {
            keep -> engine.setKeep(row.entry.ref, true)
            row.entry.localBytes > 0 -> requestRemove(row.entry.ref)
            else -> engine.setKeep(row.entry.ref, false)
        }
    }

    /**
     * Flows 6 and 8: a file on the device opens; otherwise it downloads and then opens, if this
     * screen is still shown. Folders are opened by the screen.
     */
    fun openFile(row: FileRow) {
        val entry = row.entry
        if (entry.localBytes > 0) {
            local.update { it.copy(message = FilesMessage.Opened(entry.name)) }
            return
        }
        if (!engine.downloadNow(entry.ref)) {
            local.update { it.copy(message = FilesMessage.DownloadUnavailable) }
            return
        }
        pendingOpen?.cancel()
        pendingOpen = viewModelScope.launch {
            // Ends when the file is on the device, or when no run fetches it any more (stopped).
            val final = engine.entries(folderId, entry.ref.parent)
                .map { list -> list.firstOrNull { it.ref == entry.ref }?.state }
                .first { it == null || (it !is EntryState.Downloading && it != EntryState.Waiting(queued = true)) }
            if (final == EntryState.OnDevice) local.update { it.copy(message = FilesMessage.Opened(entry.name)) }
        }
    }

    /**
     * The user left the screen (navigated away or left the app; not a configuration change):
     * a download started by a tap no longer opens the file when it ends.
     */
    fun cancelPendingOpen() {
        pendingOpen?.cancel()
        pendingOpen = null
    }

    fun showMenu(ref: FileRef) = local.update { it.copy(menu = ref) }
    fun dismissMenu() = local.update { it.copy(menu = null) }
    fun requestRemove(ref: FileRef) = local.update { it.copy(remove = ref) }

    fun onMenuAction(row: FileRow, action: MenuAction) {
        local.update { it.copy(menu = null) }
        when (action) {
            MenuAction.DOWNLOAD_NOW ->
                if (!engine.downloadNow(row.entry.ref)) local.update { it.copy(message = FilesMessage.DownloadUnavailable) }
            MenuAction.OPEN -> openFile(row)
            MenuAction.REMOVE -> requestRemove(row.entry.ref)
            MenuAction.DETAILS -> local.update { it.copy(details = row.entry.ref) }
        }
    }

    fun confirmRemove() {
        local.value.remove?.let { engine.setKeep(it, false) }
        local.update { it.copy(remove = null) }
    }

    fun dismissRemove() = local.update { it.copy(remove = null) }
    fun dismissDetails() = local.update { it.copy(details = null) }
    fun messageShown() = local.update { it.copy(message = null) }

    fun sync() {
        if (settings.preferences.value.syncNetwork != SyncNetwork.OFF) engine.startSync()
    }

    fun pause() = engine.pauseSync()
    fun resume() {
        if (uiState.value.bar.let { it is SyncBar.Running && it.resumeBlock == null }) engine.resumeSync()
    }

    fun stop() = engine.stopSync()
    fun turnOffOfflineMode() = settings.setOfflineMode(false)
    fun acceptOffer(offer: FolderOffer) = engine.acceptFolder(offer)
    fun ignoreOffer(offer: FolderOffer) = engine.ignoreFolder(offer)

    private fun build(
        status: EngineStatus,
        prefs: Preferences,
        entries: List<Entry>,
        onDevice: List<Entry>,
        ui: LocalUi,
    ): FilesUiState {
        val label = status.folders.firstOrNull { it.id == folderId }?.label ?: folderId
        // Rows only for the visible tab; menus and dialogs open from it.
        val shown = if (ui.tab == FilesTab.ALL) {
            entries.map { FileRow(it, location = null) }
        } else {
            onDevice.map { FileRow(it, location = locationOf(it, label)) }
        }
        val byRef = shown.associateBy { it.entry.ref }
        return FilesUiState(
            title = if (dir.isEmpty()) label else dir.substringAfterLast('/'),
            connection = status.connection,
            tab = ui.tab,
            sort = ui.sort,
            rows = sortRows(shown, ui.sort),
            onDeviceBytes = onDevice.sumOf { it.localBytes },
            bar = syncBar(status, prefs),
            canDownload = status.canDownload,
            offers = status.pendingOffers.map { offer ->
                offer to (status.devices.firstOrNull { it.id == offer.deviceId }?.name ?: offer.deviceId.substringBefore('-'))
            },
            menu = ui.menu?.let(byRef::get),
            confirmRemove = ui.remove?.let(byRef::get),
            details = ui.details?.let(byRef::get),
            message = ui.message,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** The reason nothing can download now, from the connection alone. */
private fun connectionBlock(status: EngineStatus): SyncBlock? = when (status.connection) {
    Connection.Disabled -> SyncBlock.OFFLINE_MODE
    Connection.Connecting -> SyncBlock.CONNECTING
    Connection.NoDevice -> SyncBlock.NO_DEVICE
    is Connection.Online -> null
}

internal fun syncBar(status: EngineStatus, prefs: Preferences): SyncBar {
    val networkOff = prefs.syncNetwork == SyncNetwork.OFF
    status.sync?.let { run ->
        val block = connectionBlock(status)
            ?: SyncBlock.NETWORK_OFF.takeIf { networkOff && run.kind == SyncKind.SYNC }
        return SyncBar.Running(
            doneBytes = run.doneBytes,
            totalBytes = run.totalBytes,
            fileNumber = run.fileNumber,
            fileCount = run.fileCount,
            paused = run.paused,
            resumeBlock = if (run.paused) block else null,
        )
    }
    val block = connectionBlock(status) ?: when {
        networkOff -> SyncBlock.NETWORK_OFF
        status.pendingBytes == 0L -> SyncBlock.NOTHING_TO_DOWNLOAD
        status.pendingBytes > status.freeBytes -> SyncBlock.NOT_ENOUGH_SPACE
        else -> null
    }
    return SyncBar.Idle(status.pendingBytes, status.freeBytes, block, status.failure)
}
