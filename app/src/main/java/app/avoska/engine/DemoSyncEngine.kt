package app.avoska.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The local copy of a remote file. */
enum class LocalCopy { NONE, CURRENT, OUTDATED }

/** A file in the demo folder's global state. */
data class DemoFile(
    val ref: FileRef,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val kind: FileKind,
    val keep: Boolean,
    val local: LocalCopy,
) {
    val needsDownload: Boolean get() = keep && local != LocalCopy.CURRENT
}

/** A sync or single-file download: [queue] in order, complete files included. */
data class DemoRun(val kind: SyncKind, val queue: List<FileRef>, val paused: Boolean)

data class DemoState(
    val thisDeviceId: String,
    val files: Map<FileRef, DemoFile>,
    val devices: List<Device>,
    val folders: List<SharedFolder>,
    val offers: List<FolderOffer>,
    val connecting: Boolean,
    val offline: Boolean,
    /** The run that downloads now, or is paused. */
    val active: DemoRun?,
    /** A paused sync set aside while a single-file download runs (flow 6). */
    val suspended: DemoRun?,
    /** Bytes received for incomplete files. Kept after Pause and Stop. */
    val partial: Map<FileRef, Long>,
    val failure: SyncFailure?,
    val freeBytes: Long,
)

/**
 * An in-memory engine with demo data. Nothing touches the network or disk. Time is simulated:
 * while [autoAdvance] is true, connections complete after a short delay and downloads advance by
 * [bytesPerTick] every [tickMillis]. Tests and debug scenes turn it off and drive the simulation
 * with [advance] and [connectPendingDevices].
 */
class DemoSyncEngine(
    private val scope: CoroutineScope,
    initial: DemoState = DemoData.initialState(),
    autoAdvance: Boolean = true,
    private val tickMillis: Long = 200L,
    private val bytesPerTick: Long = 120L * MB,
) : SyncEngine {
    private val lock = Any()
    private val state = MutableStateFlow(initial)
    private val mutableStatus = MutableStateFlow(initial.toStatus())
    override val status: StateFlow<EngineStatus> = mutableStatus.asStateFlow()
    private var ticker: Job? = null

    @Volatile
    var autoAdvance: Boolean = autoAdvance
        set(value) {
            field = value
            if (value) startTicker()
        }

    init {
        if (initial.connecting) scheduleConnect(CONNECT_DELAY_MILLIS)
    }

    override fun entries(folderId: String, dir: String): Flow<List<Entry>> =
        state.map { it.entriesAt(folderId, dir) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override fun onDevice(folderId: String): Flow<List<Entry>> =
        state.map { s ->
            s.files.values
                .filter { it.ref.folderId == folderId && it.local != LocalCopy.NONE }
                .map { s.fileEntry(it) }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override fun setKeep(ref: FileRef, keep: Boolean) = mutate { s ->
        val affected = s.filesUnder(ref).map { it.ref }.toSet()
        if (keep) {
            s.copy(files = s.files.mapAffected(affected) { it.copy(keep = true) })
        } else {
            val freed = affected.sumOf { r ->
                val f = s.files.getValue(r)
                (if (f.local != LocalCopy.NONE) f.sizeBytes else 0L) + (s.partial[r] ?: 0L)
            }
            s.copy(
                files = s.files.mapAffected(affected) { it.copy(keep = false, local = LocalCopy.NONE) },
                partial = s.partial - affected,
                active = s.active?.without(affected),
                suspended = s.suspended?.without(affected),
                freeBytes = s.freeBytes + freed,
            ).finishRunsWithoutWork()
        }
    }

    override fun downloadNow(ref: FileRef): Boolean {
        var started = false
        mutate { s ->
            val targets = s.filesUnder(ref).filter { it.local != LocalCopy.CURRENT }.map { it.ref }
            if (!s.canDownload || targets.isEmpty()) return@mutate s
            started = true
            val selected = s.copy(files = s.files.mapAffected(targets.toSet()) { it.copy(keep = true) }, failure = null)
            val active = s.active
            when {
                // A running sync fetches these first; nothing else is added.
                active != null && !active.paused ->
                    selected.copy(active = active.copy(queue = targets + (active.queue - targets.toSet())))
                // A paused run is set aside until this download ends. There is only one set-aside
                // run: a paused download replaced here rejoins it, so its files keep waiting.
                active != null -> selected.copy(
                    active = DemoRun(SyncKind.SINGLE_DOWNLOAD, targets, paused = false),
                    suspended = s.suspended.absorb(active).without(targets.toSet()),
                ).finishRunsWithoutWork()
                else -> selected.copy(
                    active = DemoRun(SyncKind.SINGLE_DOWNLOAD, targets, paused = false),
                    suspended = s.suspended?.without(targets.toSet()),
                ).finishRunsWithoutWork()
            }
        }
        if (started) startTicker()
        return started
    }

    override fun startSync(): Boolean {
        var started = false
        mutate { s ->
            val queue = s.files.values.filter { it.needsDownload }.map { it.ref }
            if (!s.canDownload || s.active != null || s.suspended != null || queue.isEmpty()) return@mutate s
            started = true
            s.copy(active = DemoRun(SyncKind.SYNC, queue, paused = false), failure = null)
        }
        if (started) startTicker()
        return started
    }

    override fun pauseSync() = mutate { s -> s.copy(active = s.active?.copy(paused = true)) }

    override fun resumeSync(): Boolean {
        var resumed = false
        mutate { s ->
            if (!s.canDownload) return@mutate s
            val run = s.active ?: s.suspended ?: return@mutate s
            resumed = true
            s.copy(active = run.copy(paused = false), suspended = if (s.active == null) null else s.suspended)
        }
        if (resumed) startTicker()
        return resumed
    }

    override fun stopSync() = mutate { s ->
        if (s.active != null) s.copy(active = null) else s.copy(suspended = null)
    }

    override fun setOffline(offline: Boolean) {
        mutate { s ->
            when {
                offline -> s.copy(offline = true, connecting = false, active = s.active?.copy(paused = true))
                s.offline -> s.copy(offline = false, connecting = true)
                else -> s
            }
        }
        if (!offline && state.value.connecting) scheduleConnect(CONNECT_DELAY_MILLIS)
    }

    override fun addDevice(deviceId: String) {
        mutate { s ->
            if (s.devices.any { it.id == deviceId }) s else s.copy(devices = s.devices + Device(deviceId, null, connected = false))
        }
        scheduleConnect(PAIRING_DELAY_MILLIS)
    }

    override fun acceptFolder(offer: FolderOffer) = mutate { s ->
        val folders = if (s.folders.any { it.id == offer.folderId }) {
            s.folders
        } else {
            s.folders + SharedFolder(offer.folderId, offer.label, deviceCount = 1)
        }
        s.copy(offers = s.offers - offer, folders = folders)
    }

    override fun ignoreFolder(offer: FolderOffer) = mutate { s -> s.copy(offers = s.offers - offer) }

    /** Simulation: finishes connecting, and new devices connect and offer a folder. */
    fun connectPendingDevices() = mutate { s ->
        val newDevices = s.devices.filter { it.name == null && !s.offline }
        val offers = newDevices
            .map { FolderOffer(DemoData.OFFERED_FOLDER_ID, DemoData.OFFERED_FOLDER_LABEL, it.id) }
            .filter { offer -> s.folders.none { it.id == offer.folderId } && offer !in s.offers }
        s.copy(
            connecting = false,
            devices = s.devices.map { if (it in newDevices) it.copy(name = DemoData.NEW_DEVICE_NAME, connected = true) else it },
            offers = s.offers + offers,
        )
    }

    /** Simulation: the running download receives [bytes]. */
    fun advance(bytes: Long) = mutate { s ->
        val run = s.active?.takeUnless { it.paused } ?: return@mutate s
        val current = s.currentOf(run) ?: return@mutate s.copy(active = null)
        val before = s.partial[current.ref] ?: 0L
        val received = minOf(before + bytes, current.sizeBytes)
        // An outdated copy is replaced in place, so only new files use up free space.
        val used = if (current.local == LocalCopy.NONE) received - before else 0L
        val next = if (received >= current.sizeBytes) {
            s.copy(
                files = s.files + (current.ref to current.copy(local = LocalCopy.CURRENT)),
                partial = s.partial - current.ref,
            )
        } else {
            s.copy(partial = s.partial + (current.ref to received))
        }
        next.copy(freeBytes = s.freeBytes - used).finishRunsWithoutWork()
    }

    private fun mutate(transform: (DemoState) -> DemoState) {
        synchronized(lock) {
            val next = transform(state.value)
            if (next != state.value) {
                state.value = next
                mutableStatus.value = next.toStatus()
            }
        }
    }

    private fun startTicker() = synchronized(lock) {
        if (!autoAdvance || ticker?.isActive == true) return
        ticker = scope.launch {
            while (autoAdvance && state.value.active?.paused == false) {
                delay(tickMillis)
                advance(bytesPerTick)
            }
        }
    }

    private fun scheduleConnect(delayMillis: Long) {
        if (!autoAdvance) return
        scope.launch {
            delay(delayMillis)
            if (autoAdvance) connectPendingDevices()
        }
    }

    private companion object {
        const val CONNECT_DELAY_MILLIS = 1_500L
        const val PAIRING_DELAY_MILLIS = 3_000L
    }
}

private val DemoState.canDownload: Boolean
    get() = !offline && !connecting && devices.any { it.connected }

private fun DemoState.filesUnder(ref: FileRef): List<DemoFile> {
    val prefix = "${ref.path}/"
    return files.values.filter { it.ref.folderId == ref.folderId && (it.ref.path == ref.path || it.ref.path.startsWith(prefix)) }
}

private fun Map<FileRef, DemoFile>.mapAffected(affected: Set<FileRef>, change: (DemoFile) -> DemoFile) =
    mapValues { (ref, file) -> if (ref in affected) change(file) else file }

/** This set-aside run with the files of [other] added at the end; [other] itself if there is none. */
private fun DemoRun?.absorb(other: DemoRun): DemoRun =
    this?.copy(queue = queue + (other.queue - queue.toSet())) ?: other

private fun DemoRun.without(refs: Set<FileRef>): DemoRun? = copy(queue = queue - refs).takeIf { it.queue.isNotEmpty() }

private fun DemoState.currentOf(run: DemoRun): DemoFile? = run.queue.firstNotNullOfOrNull { ref -> files[ref]?.takeIf { it.needsDownload } }

/** Ends runs with nothing left to download. */
private fun DemoState.finishRunsWithoutWork(): DemoState = copy(
    active = active?.takeIf { currentOf(it) != null },
    suspended = suspended?.takeIf { currentOf(it) != null },
)

private fun DemoState.received(file: DemoFile): Long =
    if (file.local == LocalCopy.CURRENT) file.sizeBytes else partial[file.ref] ?: 0L

/** The run shown to the user: the active one, or the paused sync set aside. */
private val DemoState.shownRun: DemoRun? get() = active ?: suspended

private fun DemoState.toStatus(): EngineStatus {
    val connected = if (offline) 0 else devices.count { it.connected }
    return EngineStatus(
        thisDeviceId = thisDeviceId,
        connection = when {
            offline -> Connection.Disabled
            connecting -> Connection.Connecting
            connected == 0 -> Connection.NoDevice
            else -> Connection.Online(connected, devices.size)
        },
        devices = if (offline) devices.map { it.copy(connected = false) } else devices,
        folders = folders,
        pendingOffers = offers,
        sync = shownRun?.let { run ->
            val queued = run.queue.mapNotNull { files[it] }
            val current = currentOf(run)
            SyncStatus(
                kind = run.kind,
                doneBytes = queued.sumOf { received(it) },
                totalBytes = queued.sumOf { it.sizeBytes },
                fileNumber = (queued.indexOf(current) + 1).coerceAtLeast(1),
                fileCount = queued.size,
                paused = run.paused || active == null,
            )
        },
        failure = failure,
        pendingBytes = files.values.filter { it.needsDownload }.sumOf { it.sizeBytes - (partial[it.ref] ?: 0L) },
        onDeviceBytes = files.values.filter { it.local != LocalCopy.NONE }.sumOf { it.sizeBytes },
        freeBytes = freeBytes,
    )
}

private fun DemoState.fileState(f: DemoFile): EntryState {
    val run = shownRun
    // Queued in the visible run or in the paused sync set aside behind it.
    val queued = f.needsDownload && listOfNotNull(active, suspended).any { f.ref in it.queue }
    return when {
        !f.keep -> EntryState.NotSelected
        run != null && currentOf(run)?.ref == f.ref -> EntryState.Downloading(received(f), f.sizeBytes)
        queued -> EntryState.Waiting(queued = true)
        f.local == LocalCopy.CURRENT -> EntryState.OnDevice
        f.local == LocalCopy.OUTDATED -> EntryState.Outdated
        else -> EntryState.Waiting(queued = false)
    }
}

private fun DemoState.fileEntry(f: DemoFile) = Entry(
    ref = f.ref,
    kind = f.kind,
    sizeBytes = f.sizeBytes,
    modifiedMillis = f.modifiedMillis,
    keep = f.keep,
    state = fileState(f),
    localBytes = if (f.local != LocalCopy.NONE) f.sizeBytes else 0L,
)

private fun DemoState.entriesAt(folderId: String, dir: String): List<Entry> {
    val prefix = if (dir.isEmpty()) "" else "$dir/"
    val inside = files.values.filter { it.ref.folderId == folderId && it.ref.path.startsWith(prefix) }
    val (direct, nested) = inside.partition { '/' !in it.ref.path.removePrefix(prefix) }
    val subfolders = nested.groupBy { prefix + it.ref.path.removePrefix(prefix).substringBefore('/') }
    return direct.map { fileEntry(it) } + subfolders.map { (path, content) -> folderEntry(FileRef(folderId, path), content) }
}

private fun DemoState.folderEntry(ref: FileRef, content: List<DemoFile>): Entry {
    val states = content.map { fileState(it) }
    val kept = content.count { it.keep }
    val downloading = states.any { it is EntryState.Downloading }
    val run = shownRun
    val state = when {
        kept == 0 -> EntryState.NotSelected
        downloading -> {
            val queued = content.filter { run != null && it.ref in run.queue }
            EntryState.Downloading(queued.sumOf { received(it) }, queued.sumOf { it.sizeBytes })
        }
        kept < content.size -> EntryState.SomeSelected
        states.any { it is EntryState.Waiting } -> EntryState.Waiting(states.any { it == EntryState.Waiting(true) })
        states.any { it == EntryState.Outdated } -> EntryState.Outdated
        else -> EntryState.OnDevice
    }
    return Entry(
        ref = ref,
        kind = FileKind.FOLDER,
        sizeBytes = content.sumOf { it.sizeBytes },
        modifiedMillis = content.maxOf { it.modifiedMillis },
        keep = kept == content.size,
        state = state,
        localBytes = content.filter { it.local != LocalCopy.NONE }.sumOf { it.sizeBytes },
    )
}
