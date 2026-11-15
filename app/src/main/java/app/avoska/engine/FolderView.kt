package app.avoska.engine

/**
 * One folder as the screens see it: Syncthing's global tree, the user's selection rules and the
 * local copies, combined into rows. Pure, so it runs in JVM tests.
 */
class FolderView(
    private val folderId: String,
    tree: List<RemoteEntry>,
    private val rules: Map<String, Boolean>,
    private val local: Map<String, LocalCopy>,
) {
    private val files = tree.filter { !it.isDirectory }
    private val directories = tree.filter { it.isDirectory }

    fun entriesAt(dir: String): List<Entry> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val direct = { path: String -> path.startsWith(prefix) && '/' !in path.removePrefix(prefix) }
        return files.filter { direct(it.path) }.map(::fileEntry) +
            directories.filter { direct(it.path) }.map(::folderEntry)
    }

    fun onDevice(): List<Entry> = files.filter { localOf(it) != LocalCopy.NONE }.map(::fileEntry)

    /** Bytes of selected files without a current local copy. */
    val pendingBytes: Long get() = files.filter { isKept(it.path) && localOf(it) != LocalCopy.CURRENT }.sumOf { it.sizeBytes }

    val onDeviceBytes: Long get() = files.filter { localOf(it) != LocalCopy.NONE }.sumOf { it.sizeBytes }

    private fun isKept(path: String) = SelectionStore.isKept(rules, path)

    private fun localOf(f: RemoteEntry) = local[f.path] ?: LocalCopy.NONE

    private fun fileState(f: RemoteEntry): EntryState = when {
        !isKept(f.path) -> EntryState.NotSelected
        localOf(f) == LocalCopy.CURRENT -> EntryState.OnDevice
        localOf(f) == LocalCopy.OUTDATED -> EntryState.Outdated
        // Nothing downloads in this version, so a selected file is never queued.
        else -> EntryState.Waiting(queued = false)
    }

    private fun fileEntry(f: RemoteEntry) = Entry(
        ref = FileRef(folderId, f.path),
        kind = kindOf(f.path),
        sizeBytes = f.sizeBytes,
        modifiedMillis = f.modifiedMillis,
        keep = isKept(f.path),
        state = fileState(f),
        localBytes = if (localOf(f) != LocalCopy.NONE) f.sizeBytes else 0L,
    )

    private fun folderEntry(d: RemoteEntry): Entry {
        val content = files.filter { it.path.startsWith("${d.path}/") }
        val kept = content.filter { isKept(it.path) }
        val states = kept.map(::fileState)
        val state = when {
            content.isEmpty() -> if (isKept(d.path)) EntryState.Waiting(queued = false) else EntryState.NotSelected
            kept.isEmpty() -> EntryState.NotSelected
            kept.size < content.size -> EntryState.SomeSelected
            states.any { it is EntryState.Waiting } -> EntryState.Waiting(queued = false)
            states.any { it == EntryState.Outdated } -> EntryState.Outdated
            else -> EntryState.OnDevice
        }
        return Entry(
            ref = FileRef(folderId, d.path),
            kind = FileKind.FOLDER,
            sizeBytes = kept.sumOf { it.sizeBytes },
            modifiedMillis = content.maxOfOrNull { it.modifiedMillis } ?: d.modifiedMillis,
            keep = if (content.isEmpty()) isKept(d.path) else kept.size == content.size,
            state = state,
            localBytes = content.filter { localOf(it) != LocalCopy.NONE }.sumOf { it.sizeBytes },
        )
    }

    private companion object {
        val VIDEO = setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv", "3gp")

        fun kindOf(path: String): FileKind =
            if (path.substringAfterLast('.', "").lowercase() in VIDEO) FileKind.VIDEO else FileKind.DOCUMENT
    }
}
