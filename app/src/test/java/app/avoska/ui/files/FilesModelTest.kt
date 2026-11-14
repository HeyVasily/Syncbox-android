package app.avoska.ui.files

import app.avoska.Preferences
import app.avoska.SyncNetwork
import app.avoska.engine.DemoData
import app.avoska.engine.DemoSyncEngine
import app.avoska.engine.FileRef
import app.avoska.engine.MB
import app.avoska.ui.Screen
import app.avoska.ui.startStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesModelTest {
    private val engine = DemoSyncEngine(CoroutineScope(Dispatchers.Unconfined), autoAdvance = false).apply {
        connectPendingDevices()
    }
    private fun rootRows() = runBlocking { engine.entries(DemoData.FOLDER_ID, "").first() }.map { FileRow(it, null) }

    @Test
    fun sortKeepsFoldersFirst() {
        val rows = rootRows()
        assertEquals(
            listOf(
                "Courses", "Series", "Altai trip 2024.mp4", "Conference talk.mp4", "Drone footage raw.mov",
                "Family archive 1998.mkv", "Notes.pdf",
            ),
            sortRows(rows, SortOrder.NAME).map { it.entry.name },
        )
        assertEquals(listOf("Series", "Courses", "Drone footage raw.mov"), sortRows(rows, SortOrder.SIZE).take(3).map { it.entry.name })
        assertEquals("Conference talk.mp4", sortRows(rows, SortOrder.DATE)[2].entry.name)
    }

    @Test
    fun menuOffersOnlyApplicableActions() {
        val rows = rootRows().associateBy { it.entry.name }
        assertEquals(
            listOf(MenuAction.OPEN, MenuAction.DOWNLOAD_NOW, MenuAction.REMOVE, MenuAction.DETAILS),
            rows.getValue("Conference talk.mp4").menuActions,
        )
        assertEquals(listOf(MenuAction.DOWNLOAD_NOW, MenuAction.DETAILS), rows.getValue("Drone footage raw.mov").menuActions)
        assertEquals(listOf(MenuAction.OPEN, MenuAction.REMOVE, MenuAction.DETAILS), rows.getValue("Notes.pdf").menuActions)

        engine.startSync()
        val downloading = rootRows().single { it.entry.name == "Family archive 1998.mkv" }
        assertEquals(listOf(MenuAction.DETAILS), downloading.menuActions)
    }

    @Test
    fun locationNamesTheFolderAndSubfolders() {
        val episode = runBlocking { engine.onDevice(DemoData.FOLDER_ID).first() }.first { it.ref.path.startsWith("Series/") }
        assertEquals("Movies / Series", locationOf(episode, "Movies"))
    }

    @Test
    fun barExplainsWhySyncAndResumeAreBlocked() {
        val prefs = Preferences()
        assertEquals(SyncBar.Idle(6_800 * MB, 41_000 * MB, null, null), syncBar(engine.status.value, prefs))
        assertEquals(SyncBlock.NETWORK_OFF, (syncBar(engine.status.value, prefs.copy(syncNetwork = SyncNetwork.OFF)) as SyncBar.Idle).block)

        engine.startSync()
        engine.setOffline(true)
        val paused = syncBar(engine.status.value, prefs) as SyncBar.Running
        assertTrue(paused.paused)
        assertEquals(SyncBlock.OFFLINE_MODE, paused.resumeBlock)

        // A single-file download ignores the sync network setting.
        engine.stopSync()
        engine.setOffline(false)
        engine.connectPendingDevices()
        engine.downloadNow(FileRef(DemoData.FOLDER_ID, "Drone footage raw.mov"))
        engine.pauseSync()
        val single = syncBar(engine.status.value, prefs.copy(syncNetwork = SyncNetwork.OFF)) as SyncBar.Running
        assertEquals(null, single.resumeBlock)
    }

    @Test
    fun firstLaunchStartsAtPairingWhenNoFolderIsShared() {
        val status = engine.status.value
        assertEquals(listOf(Screen.Files(DemoData.FOLDER_ID)), startStack(status))
        assertEquals(listOf(Screen.AddDevice(firstLaunch = true)), startStack(status.copy(folders = emptyList())))
        assertFalse(startStack(status).isEmpty())
    }
}
