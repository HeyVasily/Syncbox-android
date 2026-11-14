package app.avoska.ui.files

import app.avoska.engine.Entry
import app.avoska.engine.EntryState

enum class FilesTab { ALL, ON_DEVICE }

enum class SortOrder { NAME, SIZE, DATE }

enum class MenuAction { DOWNLOAD_NOW, OPEN, REMOVE, DETAILS }

/** An engine entry as a row. [location] is set on the On device tab, e.g. "Movies / Series". */
data class FileRow(val entry: Entry, val location: String?) {
    /** Only the actions that apply to the entry's state (ui.md, flow 3). */
    val menuActions: List<MenuAction>
        get() = buildList {
            val onDevice = entry.localBytes > 0
            if (onDevice && !entry.isFolder) add(MenuAction.OPEN)
            if (entry.state != EntryState.OnDevice && entry.state !is EntryState.Downloading) add(MenuAction.DOWNLOAD_NOW)
            if (onDevice) add(MenuAction.REMOVE)
            add(MenuAction.DETAILS)
        }
}

/** Folders first, then by the chosen order: name A to Z, size largest first, date newest first. */
fun sortRows(rows: List<FileRow>, order: SortOrder): List<FileRow> {
    val byName = compareBy<FileRow, String>(String.CASE_INSENSITIVE_ORDER) { it.entry.name }
    val byOrder: Comparator<FileRow> = when (order) {
        SortOrder.NAME -> byName
        SortOrder.SIZE -> compareByDescending<FileRow> { it.entry.sizeBytes }.then(byName)
        SortOrder.DATE -> compareByDescending<FileRow> { it.entry.modifiedMillis }.then(byName)
    }
    return rows.sortedWith(compareByDescending<FileRow> { it.entry.isFolder }.then(byOrder))
}

/** "Movies / Series" for a file in Series of the Movies folder. */
fun locationOf(entry: Entry, folderLabel: String): String =
    (listOf(folderLabel) + entry.ref.parent.split('/').filter { it.isNotEmpty() }).joinToString(" / ")
