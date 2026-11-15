package app.avoska.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

/*
 * Syncthing REST JSON to and from the engine's own types. Pure functions over JSON trees, so they
 * run in JVM tests; nothing from Syncthing leaks past this file and RestApi.
 */

/** The folder minDiskFree that holds every download (sync-download-gate.md). Same value as the bridge. */
val GATE_CLOSED: JsonObject = buildJsonObject {
    put("value", 1_000_000)
    put("unit", "TB")
}

data class ConfigDevice(val id: String, val name: String)

data class ConfigFolder(val id: String, val label: String, val path: String, val deviceIds: List<String>, val paused: Boolean = false)

data class ConfigSnapshot(val devices: List<ConfigDevice>, val folders: List<ConfigFolder>)

/** A file or directory in a folder's global state, from /rest/db/browse. */
data class RemoteEntry(val path: String, val isDirectory: Boolean, val sizeBytes: Long, val modifiedMillis: Long)

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.str(key: String): String = this[key].string().orEmpty()

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

/** GET /rest/config. */
fun parseConfig(json: JsonElement): ConfigSnapshot {
    val root = json.jsonObject
    return ConfigSnapshot(
        devices = root.arr("devices").map { d ->
            val o = d.jsonObject
            ConfigDevice(o.str("deviceID"), o.str("name"))
        },
        folders = root.arr("folders").map { f ->
            val o = f.jsonObject
            ConfigFolder(
                id = o.str("id"),
                label = o.str("label").ifEmpty { o.str("id") },
                path = o.str("path"),
                deviceIds = o.arr("devices").map { it.jsonObject.str("deviceID") },
                paused = (o["paused"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        },
    )
}

/** GET /rest/system/connections: the IDs of connected devices. */
fun parseConnectedDevices(json: JsonElement): Set<String> =
    json.jsonObject.obj("connections").orEmpty()
        .filterValues { (it as? JsonObject)?.get("connected")?.let { c -> (c as? JsonPrimitive)?.booleanOrNull } == true }
        .keys

/** GET /rest/cluster/pending/folders: one offer per folder and offering device. */
fun parsePendingFolders(json: JsonElement): List<FolderOffer> =
    json.jsonObject.flatMap { (folderId, value) ->
        value.jsonObject.obj("offeredBy").orEmpty().map { (deviceId, offer) ->
            FolderOffer(folderId, (offer as? JsonObject)?.str("label")?.ifEmpty { null } ?: folderId, deviceId)
        }
    }.sortedWith(compareBy({ it.label }, { it.deviceId }))

/**
 * GET /rest/db/browse without a prefix or level limit: the folder's whole global tree, flattened.
 * Symlinks are left out; the app only lists files and directories.
 */
fun parseTree(json: JsonElement): List<RemoteEntry> {
    val out = mutableListOf<RemoteEntry>()
    fun walk(items: JsonArray, prefix: String) {
        for (item in items) {
            val o = item as? JsonObject ?: continue
            val name = o.str("name")
            if (name.isEmpty()) continue
            val type = o.str("type").uppercase()
            val path = prefix + name
            val modified = parseTime(o.str("modTime"))
            when {
                "DIRECTORY" in type -> {
                    out += RemoteEntry(path, isDirectory = true, sizeBytes = 0, modifiedMillis = modified)
                    walk(o.arr("children"), "$path/")
                }
                "SYMLINK" in type -> Unit
                else -> out += RemoteEntry(path, isDirectory = false, sizeBytes = o.long("size"), modifiedMillis = modified)
            }
        }
    }
    walk(json as? JsonArray ?: JsonArray(emptyList()), "")
    return out
}

/** GET /rest/db/file: whether the local copy is the global version. */
fun parseLocalCopy(json: JsonElement): LocalCopy {
    val root = json.jsonObject
    val local = root.obj("local") ?: return LocalCopy.NONE
    val global = root.obj("global") ?: return LocalCopy.NONE
    fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    if (local.str("name").isEmpty() || local.flag("deleted") || local.flag("ignored") || local.flag("invalid")) {
        return LocalCopy.NONE
    }
    val same = local["version"] == global["version"] && local.long("size") == global.long("size") &&
        local["blocksHash"] == global["blocksHash"]
    return if (same) LocalCopy.CURRENT else LocalCopy.OUTDATED
}

private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

private fun parseTime(text: String): Long = runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0L)

/** GET /rest/events: the last event ID, and the folders whose file lists changed. */
data class EventBatch(val lastId: Long, val count: Int, val changedFolders: Set<String>, val configChanged: Boolean)

private val INDEX_EVENTS = setOf("LocalIndexUpdated", "RemoteIndexUpdated", "FolderSummary")

fun parseEvents(json: JsonElement, since: Long): EventBatch {
    val events = json as? JsonArray ?: return EventBatch(since, 0, emptySet(), false)
    var last = since
    val folders = mutableSetOf<String>()
    var config = false
    for (e in events) {
        val o = e as? JsonObject ?: continue
        last = maxOf(last, o.long("id"))
        val type = o.str("type")
        if (type in INDEX_EVENTS) o.obj("data")?.str("folder")?.takeIf { it.isNotEmpty() }?.let(folders::add)
        if (type == "ConfigSaved") config = true
    }
    return EventBatch(last, events.size, folders, config)
}

/** The event types the app listens to; everything else is filtered out by Syncthing. */
val WATCHED_EVENTS = listOf(
    "ConfigSaved", "DeviceConnected", "DeviceDisconnected", "DevicePaused", "DeviceResumed",
    "PendingFoldersChanged", "LocalIndexUpdated", "RemoteIndexUpdated", "FolderSummary",
)

/** PUT /rest/config/devices/{id}: a new device from the defaults, found through discovery. */
fun newDeviceConfig(defaults: JsonElement, deviceId: String): JsonObject =
    JsonObject(defaults.jsonObject + mapOf("deviceID" to JsonPrimitive(deviceId)))

/**
 * PUT /rest/config/folders/{id} for an accepted offer: receive-only, shared with the offering
 * device, created paused with the download gate closed. It is unpaused only after its ignores
 * are `*`, so accepting it downloads nothing.
 */
fun newFolderConfig(defaults: JsonElement, offer: FolderOffer, path: String): JsonObject =
    JsonObject(
        defaults.jsonObject + mapOf(
            "id" to JsonPrimitive(offer.folderId),
            "label" to JsonPrimitive(offer.label),
            "path" to JsonPrimitive(path),
            "type" to JsonPrimitive("receiveonly"),
            "paused" to JsonPrimitive(true),
            "devices" to JsonArray(listOf(buildJsonObject { put("deviceID", offer.deviceId) })),
            "minDiskFree" to GATE_CLOSED,
        ),
    )

/**
 * PATCH /rest/config/folders/{id} body that adds [deviceId] to the folder's devices and keeps the
 * ones it has (a PATCH replaces the whole list).
 */
fun folderDevicesPatch(folder: JsonElement, deviceId: String): JsonObject {
    val devices = (folder.jsonObject["devices"] as? JsonArray).orEmpty()
    val known = devices.any { (it as? JsonObject)?.str("deviceID") == deviceId }
    val added = if (known) devices else devices + buildJsonObject { put("deviceID", deviceId) }
    return buildJsonObject { put("devices", JsonArray(added)) }
}

/** PATCH /rest/config/devices/{id} body that adds [offer] to the device's ignored folders. */
fun ignoreFolderPatch(device: JsonElement, offer: FolderOffer, now: Instant): JsonObject {
    val existing = (device.jsonObject["ignoredFolders"] as? JsonArray).orEmpty()
        .filter { (it as? JsonObject)?.str("id") != offer.folderId }
    val added = buildJsonObject {
        put("time", now.toString())
        put("id", offer.folderId)
        put("label", offer.label)
    }
    return buildJsonObject { put("ignoredFolders", JsonArray(existing + added)) }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
