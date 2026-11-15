package app.avoska.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The last known device ID, devices and folders, so the app opens on the right screen (Files, not
 * pairing) before Syncthing has started, and in offline mode. The file list itself is not cached
 * yet: that is a follow-up.
 */
data class CachedStatus(val thisDeviceId: String, val devices: List<Device>, val folders: List<SharedFolder>)

class StatusCache(private val file: File) {
    fun load(): CachedStatus? = runCatching {
        if (!file.exists()) return null
        decodeStatus(file.readText())
    }.getOrNull()

    fun save(status: CachedStatus) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(encodeStatus(status))
        tmp.renameTo(file)
    }
}

internal fun encodeStatus(s: CachedStatus): String = buildJsonObject {
    put("thisDeviceId", s.thisDeviceId)
    put("devices", JsonArray(s.devices.map { d -> buildJsonObject { put("id", d.id); put("name", d.name) } }))
    put(
        "folders",
        JsonArray(s.folders.map { f -> buildJsonObject { put("id", f.id); put("label", f.label); put("deviceCount", f.deviceCount) } }),
    )
}.toString()

internal fun decodeStatus(text: String): CachedStatus {
    val root = Json.parseToJsonElement(text).jsonObject
    fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    return CachedStatus(
        thisDeviceId = root.s("thisDeviceId").orEmpty(),
        devices = (root["devices"] as? JsonArray).orEmpty().map {
            val o = it.jsonObject
            Device(o.s("id").orEmpty(), o.s("name"), connected = false)
        },
        folders = (root["folders"] as? JsonArray).orEmpty().map {
            val o = it.jsonObject
            SharedFolder(o.s("id").orEmpty(), o.s("label").orEmpty(), (o["deviceCount"] as? JsonPrimitive)?.intOrNull ?: 0)
        },
    )
}
