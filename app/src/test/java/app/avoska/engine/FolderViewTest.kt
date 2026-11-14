package app.avoska.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderViewTest {
    private val tree = listOf(
        RemoteEntry("Series", isDirectory = true, sizeBytes = 0, modifiedMillis = 10),
        RemoteEntry("Series/Episode 01.mkv", isDirectory = false, sizeBytes = 2_000, modifiedMillis = 30),
        RemoteEntry("Series/Episode 02.mkv", isDirectory = false, sizeBytes = 3_000, modifiedMillis = 20),
        RemoteEntry("Empty", isDirectory = true, sizeBytes = 0, modifiedMillis = 5),
        RemoteEntry("Notes.pdf", isDirectory = false, sizeBytes = 100, modifiedMillis = 40),
    )

    private fun view(rules: Map<String, Boolean> = emptyMap(), local: Map<String, LocalCopy> = emptyMap()) =
        FolderView("media", tree, rules, local)

    private fun List<Entry>.byPath() = associateBy { it.ref.path }

    @Test
    fun rootListsDirectFilesAndFolders() {
        val rows = view().entriesAt("").byPath()
        assertEquals(setOf("Notes.pdf", "Series", "Empty"), rows.keys)
        val series = rows.getValue("Series")
        assertEquals(FileKind.FOLDER, series.kind)
        assertEquals(EntryState.NotSelected, series.state)
        assertEquals(0L, series.sizeBytes) // nothing selected
        assertEquals(30L, series.modifiedMillis) // newest content
        assertEquals(FileKind.DOCUMENT, rows.getValue("Notes.pdf").kind)
        assertEquals(setOf("Series/Episode 01.mkv", "Series/Episode 02.mkv"), view().entriesAt("Series").byPath().keys)
        assertEquals(FileKind.VIDEO, view().entriesAt("Series").first().kind)
    }

    @Test
    fun selectionDrivesStatesSizesAndPendingBytes() {
        val partly = view(rules = mapOf("Series/Episode 02.mkv" to true))
        val series = partly.entriesAt("").byPath().getValue("Series")
        assertEquals(EntryState.SomeSelected, series.state)
        assertEquals(3_000L, series.sizeBytes)
        assertEquals(false, series.keep)
        assertEquals(EntryState.Waiting(queued = false), partly.entriesAt("Series").byPath().getValue("Series/Episode 02.mkv").state)
        assertEquals(3_000L, partly.pendingBytes)

        val all = view(rules = mapOf("Series" to true))
        val whole = all.entriesAt("").byPath().getValue("Series")
        assertEquals(true, whole.keep)
        assertEquals(EntryState.Waiting(queued = false), whole.state)
        assertEquals(5_000L, all.pendingBytes)
    }

    @Test
    fun localCopiesShowOnDeviceAndOutdated() {
        val v = view(
            rules = mapOf("Series" to true, "Notes.pdf" to true),
            local = mapOf("Series/Episode 01.mkv" to LocalCopy.CURRENT, "Notes.pdf" to LocalCopy.OUTDATED),
        )
        val series = v.entriesAt("Series").byPath()
        assertEquals(EntryState.OnDevice, series.getValue("Series/Episode 01.mkv").state)
        assertEquals(2_000L, series.getValue("Series/Episode 01.mkv").localBytes)
        assertEquals(EntryState.Outdated, v.entriesAt("").byPath().getValue("Notes.pdf").state)
        assertEquals(setOf("Series/Episode 01.mkv", "Notes.pdf"), v.onDevice().map { it.ref.path }.toSet())
        assertEquals(2_100L, v.onDeviceBytes)
        assertEquals(3_100L, v.pendingBytes) // Episode 02 missing, Notes outdated
    }
}
