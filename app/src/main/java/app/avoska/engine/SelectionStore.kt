package app.avoska.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * What the user chose to keep on the device, per folder: the selection intent. It is the app's
 * own record, separate from Syncthing's ignore file (build-strategy.md), and survives restarts.
 *
 * Each folder holds rules from a path to keep or not keep. A path's choice is that of its nearest
 * rule, itself or an ancestor; with no rule it is not kept. So keeping a folder also keeps files
 * added to it later, and a file inside can still be left out.
 */
class SelectionStore(private val file: File) {
    private val lock = Any()
    private val mutable = MutableStateFlow(load())

    /** Folder ID to rules. */
    val rules: StateFlow<Map<String, Map<String, Boolean>>> = mutable.asStateFlow()

    fun set(folderId: String, path: String, keep: Boolean) = synchronized(lock) {
        val all = mutable.value
        val folder = all[folderId].orEmpty()
        val next = folder.filterKeys { !it.isAtOrUnder(path) }
            .let { rest -> if (isKept(rest, path.parentPath()) == keep && path.isNotEmpty()) rest else rest + (path to keep) }
        if (next != folder) {
            val updated = if (next.isEmpty()) all - folderId else all + (folderId to next)
            trySave(updated)
            mutable.value = updated
        }
    }

    fun removeFolder(folderId: String) = synchronized(lock) {
        val all = mutable.value
        if (folderId in all) {
            trySave(all - folderId)
            mutable.value = all - folderId
        }
    }

    private fun load(): Map<String, Map<String, Boolean>> = runCatching {
        if (!file.exists()) return emptyMap()
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        root["folders"]?.jsonObject.orEmpty().mapValues { (_, rules) ->
            rules.jsonObject.mapNotNull { (path, keep) -> (keep as? JsonPrimitive)?.booleanOrNull?.let { path to it } }.toMap()
        }
    }.getOrDefault(emptyMap())

    /**
     * Called on the UI thread, so it never throws: if the file cannot be written (disk full), the
     * choice still holds in memory and is written with the next change that succeeds.
     */
    private fun trySave(all: Map<String, Map<String, Boolean>>) {
        runCatching { save(all) }
    }

    /** Written to a temporary file and renamed, so a crash never leaves half a file. */
    private fun save(all: Map<String, Map<String, Boolean>>) {
        val json = buildJsonObject {
            put("version", 1)
            put(
                "folders",
                JsonObject(all.mapValues { (_, rules) -> JsonObject(rules.mapValues { JsonPrimitive(it.value) }) }),
            )
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) error("Could not write $file")
    }

    companion object {
        /** Whether [path] is kept under [rules]: the nearest rule at or above it decides. */
        fun isKept(rules: Map<String, Boolean>, path: String): Boolean {
            var p = path
            while (true) {
                rules[p]?.let { return it }
                if (p.isEmpty()) return false
                p = p.parentPath()
            }
        }
    }
}

/** The parent directory of a folder-relative path; "" for top-level entries and for "" itself. */
internal fun String.parentPath(): String = substringBeforeLast('/', missingDelimiterValue = "")

/** Whether this path is [dir] itself or inside it. "" is the folder root, which contains everything. */
internal fun String.isAtOrUnder(dir: String): Boolean = dir.isEmpty() || this == dir || startsWith("$dir/")
