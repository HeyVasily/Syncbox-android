package app.avoska.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the screens need from Syncthing. The demo engine implements it in memory; the real
 * engine (gomobile bridge plus REST) will implement the same interface later.
 *
 * [status] is small and changes often. File lists are queried per folder level, so no caller ever
 * holds the whole tree. Every file reference carries its folder ID.
 */
interface SyncEngine {
    val status: StateFlow<EngineStatus>

    /** Files and subfolders directly in [dir] ("" is the folder root), with state, size and progress. */
    fun entries(folderId: String, dir: String): Flow<List<Entry>>

    /** Every file with a local copy in the folder, across subfolders. */
    fun onDevice(folderId: String): Flow<List<Entry>>

    /** Selects or deselects a file, or everything under a folder. Deselecting deletes local copies. */
    fun setKeep(ref: FileRef, keep: Boolean)

    /**
     * Flow 6: downloads this file or folder at once, on any network, without starting anything else
     * that is selected and without resuming a paused sync. Selects it only if the download can start.
     * Returns false when it cannot: offline, connecting, no device connected, or nothing to download.
     */
    fun downloadNow(ref: FileRef): Boolean

    // Sync lifecycle. A user-initiated data transfer job will own these once it exists.
    fun startSync(): Boolean
    fun pauseSync()

    /** Resumes a paused sync or download; does nothing while it cannot download. */
    fun resumeSync(): Boolean
    fun stopSync()

    /** Offline mode: when true the engine does not connect and uses no network. */
    fun setOffline(offline: Boolean)

    /** Flow 1: adds a device by a validated ID and waits for it to connect. */
    fun addDevice(deviceId: String)
    fun acceptFolder(offer: FolderOffer)
    fun ignoreFolder(offer: FolderOffer)
}

data class FileRef(val folderId: String, val path: String) {
    val name: String get() = path.substringAfterLast('/')

    /** The directory that contains this entry; "" for the folder root. */
    val parent: String get() = path.substringBeforeLast('/', missingDelimiterValue = "")
}

enum class FileKind { FOLDER, VIDEO, DOCUMENT }

/** What a row says about an entry. Folders aggregate the state of their content. */
sealed interface EntryState {
    data object NotSelected : EntryState
    data object SomeSelected : EntryState

    /** Selected, not on the device yet. [queued] is true while a sync or download will fetch it. */
    data class Waiting(val queued: Boolean) : EntryState
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : EntryState
    data object OnDevice : EntryState
    data object Outdated : EntryState
}

data class Entry(
    val ref: FileRef,
    val kind: FileKind,
    /** Folders: total size of their content. */
    val sizeBytes: Long,
    val modifiedMillis: Long,
    /** The keep switch: for a folder, true when everything inside is selected. */
    val keep: Boolean,
    val state: EntryState,
    /** Bytes of local copies; what removing it would free. */
    val localBytes: Long,
) {
    val name: String get() = ref.name
    val isFolder: Boolean get() = kind == FileKind.FOLDER
}

data class Device(val id: String, val name: String?, val connected: Boolean)

data class SharedFolder(val id: String, val label: String, val deviceCount: Int)

/** A folder a connected device wants to share with this phone. */
data class FolderOffer(val folderId: String, val label: String, val deviceId: String)

sealed interface Connection {
    data object Connecting : Connection
    data class Online(val connected: Int, val total: Int) : Connection
    /** Running, but no device is connected. */
    data object NoDevice : Connection
    /** Offline mode: the engine does not connect. */
    data object Disabled : Connection
}

enum class SyncKind { SYNC, SINGLE_DOWNLOAD }

data class SyncStatus(
    val kind: SyncKind,
    val doneBytes: Long,
    val totalBytes: Long,
    val fileNumber: Int,
    val fileCount: Int,
    val paused: Boolean,
)

/** Flow 7: why the last sync ended without finishing. */
enum class SyncFailure { CONNECTION_LOST, OUT_OF_SPACE }

data class EngineStatus(
    val thisDeviceId: String,
    val connection: Connection,
    val devices: List<Device>,
    val folders: List<SharedFolder>,
    val pendingOffers: List<FolderOffer>,
    /** The running or paused sync or single-file download, if any. */
    val sync: SyncStatus?,
    val failure: SyncFailure?,
    /** Bytes still to download for everything selected, in all folders. */
    val pendingBytes: Long,
    /** Bytes of local copies, current or outdated, in all folders. */
    val onDeviceBytes: Long,
    val freeBytes: Long,
) {
    val canDownload: Boolean get() = connection is Connection.Online
}
