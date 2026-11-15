package app.avoska.engine

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant

/**
 * The real engine: Syncthing in this process (through [process]), driven over its REST API.
 *
 * Syncthing runs only while the app is visible and offline mode is off. It always runs with the
 * download gate closed, so in this version nothing is ever downloaded: the file list refreshes,
 * pairing and folder offers work, and the selection is only recorded. Downloads come with the
 * background jobs of the next milestone; until then every download command reports that it is
 * unavailable ([EngineStatus.downloadsAvailable] is false).
 */
class RestSyncEngine(
    private val scope: CoroutineScope,
    private val process: SyncthingProcess,
    /** Syncthing's home: config, keys and database. */
    private val home: File,
    /** Where shared folders are stored, one directory per folder. */
    private val dataRoot: File,
    private val selection: SelectionStore,
    private val cache: StatusCache,
) : SyncEngine {
    private data class Live(
        val running: Boolean = false,
        /** Set once the first connections had time to come up, so "no device" is not shown too early. */
        val settled: Boolean = false,
        val failure: String? = null,
        val deviceId: String,
        val config: ConfigSnapshot? = null,
        val connected: Set<String> = emptySet(),
        val offers: List<FolderOffer> = emptyList(),
    )

    /** One folder's global tree and local copies, as last read. */
    private data class FolderData(val tree: List<RemoteEntry>, val local: Map<String, LocalCopy>)

    private val cached = cache.load()
    private var lastCached = cached
    private val visible = MutableStateFlow(false)
    private val offline = MutableStateFlow(false)
    private val live = MutableStateFlow(Live(deviceId = cached?.thisDeviceId.orEmpty()))
    private val folders = MutableStateFlow<Map<String, FolderData>>(emptyMap())

    /**
     * Serializes everything that reads, changes and writes back Syncthing's config or ignores:
     * pairing commands and the finishing of interrupted accepts. Without it two accepts of the
     * same folder could read the same device list and the second would drop the first's device.
     */
    private val configLock = Mutex()

    /** The running session's API, or null between sessions. */
    private val api = MutableStateFlow<RestApi?>(null)
    @Volatile private var refreshRequests: Channel<Set<String>?>? = null

    override val status: StateFlow<EngineStatus> =
        combine(live, folders, selection.rules, offline, ::buildStatus)
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, buildStatus(live.value, emptyMap(), selection.rules.value, offline.value))

    init {
        scope.launch(Dispatchers.IO) {
            combine(visible, offline) { v, o -> v && !o }.distinctUntilChanged().collectLatest { run ->
                if (run) runSession()
            }
        }
    }

    override fun setAppVisible(visible: Boolean) {
        this.visible.value = visible
    }

    override fun setOffline(offline: Boolean) {
        this.offline.value = offline
    }

    override fun entries(folderId: String, dir: String): Flow<List<Entry>> = folderView(folderId).map { it.entriesAt(dir) }
        .distinctUntilChanged().flowOn(Dispatchers.Default)

    override fun onDevice(folderId: String): Flow<List<Entry>> = folderView(folderId).map { it.onDevice() }
        .distinctUntilChanged().flowOn(Dispatchers.Default)

    /** Emits once the folder's list has been read; nothing before, so "loading" never looks empty. */
    private fun folderView(folderId: String): Flow<FolderView> =
        combine(folders.map { it[folderId] }.filterNotNull(), selection.rules.map { it[folderId].orEmpty() }) { data, rules ->
            FolderView(folderId, data.tree, rules, data.local)
        }

    /** Records the choice only; nothing is armed or deleted until downloads exist (next milestone). */
    override fun setKeep(ref: FileRef, keep: Boolean) = selection.set(ref.folderId, ref.path, keep)

    override fun downloadNow(ref: FileRef): Boolean = false
    override fun startSync(): Boolean = false
    override fun pauseSync() = Unit
    override fun resumeSync(): Boolean = false
    override fun stopSync() = Unit

    override fun addDevice(deviceId: String) = command("add device") { api ->
        api.put("rest", "config", "devices", deviceId, body = newDeviceConfig(api.get("rest", "config", "defaults", "device"), deviceId))
    }

    /**
     * Adds the folder as receive-only with the gate closed, paused until its ignores are `*`, so
     * that accepting it downloads nothing (the harness order: paused, ignores, unpause). If the
     * folder already exists (offered by another device too), the offering device is added to it.
     */
    override fun acceptFolder(offer: FolderOffer) = command("accept folder") { api ->
        val existing = parseConfig(api.get("rest", "config")).folders.firstOrNull { it.id == offer.folderId }
        if (existing != null) {
            if (offer.deviceId !in existing.deviceIds) {
                val folder = api.get("rest", "config", "folders", offer.folderId)
                api.patch("rest", "config", "folders", offer.folderId, body = folderDevicesPatch(folder, offer.deviceId))
            }
            return@command
        }
        val dir = File(dataRoot, folderDirName(offer.folderId))
        val defaults = api.get("rest", "config", "defaults", "folder")
        prepareFolderDir(dir)
        api.put("rest", "config", "folders", offer.folderId, body = newFolderConfig(defaults, offer, dir.absolutePath))
        finishAccept(api, offer.folderId, dir)
    }

    private fun prepareFolderDir(dir: File) {
        File(dir, ".stfolder").mkdirs()
        File(dir, ".stignore").writeText("*\n")
    }

    /**
     * The end of an accept, safe to repeat: ignores `*`, gate closed, then unpaused. Also run for
     * a folder an earlier accept left paused (the app was stopped in between).
     */
    private fun finishAccept(api: RestApi, folderId: String, dir: File) {
        prepareFolderDir(dir)
        api.post(
            "rest", "db", "ignores",
            query = mapOf("folder" to folderId),
            body = buildJsonObject { put("ignore", JsonArray(listOf(JsonPrimitive("*")))) },
        )
        api.patch(
            "rest", "config", "folders", folderId,
            body = buildJsonObject {
                put("minDiskFree", GATE_CLOSED)
                put("paused", false)
            },
        )
    }

    override fun ignoreFolder(offer: FolderOffer) = command("ignore folder") { api ->
        val device = api.get("rest", "config", "devices", offer.deviceId)
        api.patch("rest", "config", "devices", offer.deviceId, body = ignoreFolderPatch(device, offer, Instant.now()))
    }

    /**
     * Runs a pairing command in the current Syncthing session, or queues it until the next one
     * (Syncthing still starting, or offline mode): commands are never dropped silently. A command
     * cut off by the session's end runs again in the next one; the commands are idempotent, and they
     * run one at a time ([configLock]), each reading the config afresh. Queued
     * commands are lost if the process dies first.
     */
    private fun command(name: String, block: (RestApi) -> Unit) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                if (api.value == null) Log.i(TAG, "$name: waiting for Syncthing to run")
                val current = api.filterNotNull().first()
                try {
                    configLock.withLock { block(current) }
                    refreshRequests?.trySend(null)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (api.value === current && process.isRunning()) {
                        Log.w(TAG, "$name failed", e)
                        return@launch
                    }
                    Log.i(TAG, "$name: session ended, retrying in the next one")
                    api.first { it !== current }
                }
            }
        }
    }

    /** One Syncthing run, from start until the coroutine is cancelled (app hidden or offline mode). */
    private suspend fun runSession() {
        val t0 = System.nanoTime()
        val session = try {
            process.start(home)
        } catch (e: Exception) {
            Log.e(TAG, "Syncthing did not start", e)
            live.update { it.copy(running = false, failure = e.message ?: e.toString()) }
            return
        }
        Log.i(TAG, "Syncthing started: device ${session.deviceId}, REST ${session.apiAddress}, ${millisSince(t0)} ms")
        val rest = RestApi(session)
        val requests = Channel<Set<String>?>(Channel.UNLIMITED)
        api.value = rest
        refreshRequests = requests
        live.update { it.copy(running = true, settled = false, failure = null, deviceId = session.deviceId) }
        try {
            coroutineScope {
                launch {
                    delay(SETTLE_MILLIS)
                    live.update { it.copy(settled = true) }
                }
                launch { watchEvents(rest, requests) }
                requests.send(null)
                for (first in requests) {
                    // Events come in bursts; one refresh covers a burst. null means everything.
                    delay(REFRESH_DELAY_MILLIS)
                    var changed: Set<String>? = first
                    while (true) {
                        val received = requests.tryReceive()
                        if (!received.isSuccess) break
                        val next = received.getOrNull()
                        changed = if (changed == null || next == null) null else changed + next
                    }
                    refresh(rest, changed)
                }
                // The channel closes only when Syncthing stopped by itself.
                coroutineContext.cancelChildren()
            }
        } finally {
            api.value = null
            refreshRequests = null
            withContext(NonCancellable) {
                val t1 = System.nanoTime()
                process.stop()
                Log.i(TAG, "Syncthing stopped in ${millisSince(t1)} ms")
                live.update { it.copy(running = false, connected = emptySet(), offers = emptyList()) }
            }
        }
    }

    private suspend fun watchEvents(rest: RestApi, requests: Channel<Set<String>?>) {
        var since = 0L
        while (true) {
            try {
                val batch = withContext(Dispatchers.IO) { parseEvents(rest.events(since), since) }
                since = batch.lastId
                if (batch.count > 0) requests.send(if (batch.configChanged) null else batch.changedFolders)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!process.isRunning()) {
                    val reason = process.failure().ifEmpty { "Syncthing stopped" }
                    Log.e(TAG, "Syncthing stopped by itself: $reason")
                    live.update { it.copy(failure = reason) }
                    requests.close()
                    return
                }
                Log.w(TAG, "events: ${e.message}")
                delay(RETRY_MILLIS)
            }
        }
    }

    /** Reads devices, connections and offers, then the file lists of [changed] folders (null: all). */
    private suspend fun refresh(rest: RestApi, changed: Set<String>?) = withContext(Dispatchers.IO) {
        try {
            val config = parseConfig(rest.get("rest", "config"))
            val connected = parseConnectedDevices(rest.get("rest", "system", "connections"))
            val offers = parsePendingFolders(rest.get("rest", "cluster", "pending", "folders"))
            live.update { it.copy(config = config, connected = connected, offers = offers) }
            saveCache()
            // Re-read under the lock: a command may have changed the folders since the read above.
            configLock.withLock { resumeInterruptedAccepts(rest, parseConfig(rest.get("rest", "config"))) }
            val known = config.folders.associateBy { it.id }
            val updates = known.values.filter { changed == null || it.id in changed || it.id !in folders.value }
                .mapNotNull { f -> readFolder(rest, f)?.let { f.id to it } }
            folders.update { current -> current.filterKeys { it in known } + updates }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IO, or a response the parsers did not expect: never crash the app over a refresh.
            Log.w(TAG, "refresh: $e")
        }
    }

    /** Finishes accepts that left an app-created folder paused; the config change triggers a refresh. */
    private fun resumeInterruptedAccepts(rest: RestApi, config: ConfigSnapshot) {
        val root = dataRoot.absoluteFile
        config.folders.filter { it.paused && File(it.path).absoluteFile.parentFile == root }.forEach { f ->
            Log.i(TAG, "folder ${f.id}: finishing an interrupted accept")
            try {
                finishAccept(rest, f.id, File(f.path))
            } catch (e: IOException) {
                Log.w(TAG, "folder ${f.id}: could not finish the accept: ${e.message}")
            }
        }
    }

    /** The folder's global tree, plus which files have a local copy. Null if it cannot be read now (paused). */
    private fun readFolder(rest: RestApi, folder: ConfigFolder): FolderData? = try {
        val tree = parseTree(rest.get("rest", "db", "browse", query = mapOf("folder" to folder.id)))
        val remote = tree.filter { !it.isDirectory }.map { it.path }.toSet()
        val local = localFiles(File(folder.path)).filter { it in remote }.associateWith { path ->
            parseLocalCopy(rest.get("rest", "db", "file", query = mapOf("folder" to folder.id, "file" to path)))
        }.filterValues { it != LocalCopy.NONE }
        FolderData(tree, local)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "folder ${folder.id}: $e")
        null
    }

    private fun saveCache() {
        val l = live.value
        val next = CachedStatus(l.deviceId, buildDevices(l).map { it.copy(connected = false) }, buildFolders(l))
        if (next != lastCached) {
            runCatching { cache.save(next) }.onFailure { Log.w(TAG, "status cache", it) }
            lastCached = next
        }
    }

    private fun buildDevices(l: Live): List<Device> = l.config?.devices?.filter { it.id != l.deviceId }
        ?.map { Device(it.id, it.name.ifBlank { null }, connected = l.running && it.id in l.connected) }
        ?: cached?.devices.orEmpty()

    private fun buildFolders(l: Live): List<SharedFolder> = l.config?.folders
        ?.map { f -> SharedFolder(f.id, f.label, f.deviceIds.count { it != l.deviceId }) }
        ?: cached?.folders.orEmpty()

    private fun buildStatus(l: Live, data: Map<String, FolderData>, rules: Map<String, Map<String, Boolean>>, offline: Boolean): EngineStatus {
        val devices = buildDevices(l)
        val connected = devices.count { it.connected }
        val views = data.map { (id, d) -> FolderView(id, d.tree, rules[id].orEmpty(), d.local) }
        return EngineStatus(
            thisDeviceId = l.deviceId,
            connection = when {
                offline -> Connection.Disabled
                l.failure != null -> Connection.Failed(l.failure)
                !l.running -> Connection.Connecting
                connected > 0 -> Connection.Online(connected, devices.size)
                !l.settled && devices.isNotEmpty() -> Connection.Connecting
                else -> Connection.NoDevice
            },
            devices = devices,
            folders = buildFolders(l),
            pendingOffers = if (l.running) l.offers else emptyList(),
            sync = null,
            failure = null,
            pendingBytes = views.sumOf { it.pendingBytes },
            onDeviceBytes = views.sumOf { it.onDeviceBytes },
            freeBytes = dataRoot.usableSpace.takeIf { it > 0 } ?: dataRoot.parentFile?.usableSpace ?: 0L,
            downloadsAvailable = false,
        )
    }

    private companion object {
        const val TAG = "AvoskaEngine"
        const val SETTLE_MILLIS = 15_000L
        const val REFRESH_DELAY_MILLIS = 250L
        const val RETRY_MILLIS = 1_000L

        fun millisSince(t0: Long) = (System.nanoTime() - t0) / 1_000_000


        /** Regular files under [root], as folder-relative paths, without Syncthing's own files. */
        fun localFiles(root: File): List<String> {
            if (!root.isDirectory) return emptyList()
            return root.walkTopDown()
                .onEnter { it == root || it.name != ".stfolder" }
                .filter { it.isFile && !it.name.startsWith(".syncthing.") && it.name != ".stignore" }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        }
    }
}

/**
 * The directory for a folder ID, one per ID: the ID's safe characters for readability,
 * plus a hash of the whole ID, so IDs that read the same (`.media`, `media`, `a/b`, `a_b`)
 * never share a directory.
 */
internal fun folderDirName(folderId: String): String {
    val readable = folderId.map { if (it.isLetterOrDigit() || it in "-_") it else '_' }.joinToString("").take(40)
    val hash = MessageDigest.getInstance("SHA-256").digest(folderId.toByteArray())
        .take(6).joinToString("") { "%02x".format(it) }
    return "${readable.ifEmpty { "folder" }}-$hash"
}
