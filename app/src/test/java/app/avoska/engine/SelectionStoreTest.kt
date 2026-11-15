package app.avoska.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SelectionStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(file: File = File(tmp.root, "selection.json")) = SelectionStore(file)

    private fun SelectionStore.kept(folder: String, path: String) = SelectionStore.isKept(rules.value[folder].orEmpty(), path)

    @Test
    fun nothingIsKeptByDefault() {
        val s = store()
        assertFalse(s.kept("media", "a.mkv"))
        assertEquals(emptyMap<String, Map<String, Boolean>>(), s.rules.value)
    }

    @Test
    fun keepingAFolderKeepsEverythingInsideIncludingLaterFiles() {
        val s = store()
        s.set("media", "Series", true)
        assertTrue(s.kept("media", "Series"))
        assertTrue(s.kept("media", "Series/Episode 01.mkv"))
        assertTrue(s.kept("media", "Series/Season 2/new file.mkv"))
        assertFalse(s.kept("media", "Series 2/x.mkv")) // a sibling with the same prefix
        assertFalse(s.kept("other", "Series/Episode 01.mkv"))
    }

    @Test
    fun aFileInsideAKeptFolderCanBeLeftOutAndBackIn() {
        val s = store()
        s.set("media", "Series", true)
        s.set("media", "Series/Episode 02.mkv", false)
        assertFalse(s.kept("media", "Series/Episode 02.mkv"))
        assertTrue(s.kept("media", "Series/Episode 01.mkv"))
        s.set("media", "Series/Episode 02.mkv", true)
        // Back to the folder rule alone: no redundant rule is left.
        assertEquals(mapOf("Series" to true), s.rules.value["media"])
    }

    @Test
    fun choosingAFolderReplacesTheRulesInsideIt() {
        val s = store()
        s.set("media", "Series/Episode 01.mkv", true)
        s.set("media", "Series/Season 2", true)
        s.set("media", "Series", false)
        assertFalse(s.kept("media", "Series/Episode 01.mkv"))
        assertEquals(null, s.rules.value["media"]) // nothing kept, so the folder has no rules
    }

    @Test
    fun selectionSurvivesARestartAndIgnoresACorruptFile() {
        val file = File(tmp.root, "selection.json")
        store(file).apply {
            set("media", "Notes #1 [draft].txt", true)
            set("media", "Series", true)
            set("media", "Series/Episode 02.mkv", false)
        }
        val reloaded = store(file)
        assertTrue(reloaded.kept("media", "Notes #1 [draft].txt"))
        assertTrue(reloaded.kept("media", "Series/Episode 01.mkv"))
        assertFalse(reloaded.kept("media", "Series/Episode 02.mkv"))
        assertFalse(File(file.path + ".tmp").exists())

        file.writeText("{not json")
        assertEquals(emptyMap<String, Map<String, Boolean>>(), store(file).rules.value)
    }

    @Test
    fun removingAFolderDropsItsRules() {
        val file = File(tmp.root, "selection.json")
        val s = store(file)
        s.set("media", "a.mkv", true)
        s.set("photos", "b.jpg", true)
        s.removeFolder("media")
        assertEquals(setOf("photos"), store(file).rules.value.keys)
    }
}
