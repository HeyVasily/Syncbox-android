package app.avoska.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoSyncEngineTest {
    private val engine = DemoSyncEngine(CoroutineScope(Dispatchers.Unconfined), autoAdvance = false).apply {
        connectPendingDevices()
    }
    private val status get() = engine.status.value
    private fun ref(path: String) = FileRef(DemoData.FOLDER_ID, path)
    private fun entry(path: String): Entry = runBlocking {
        engine.entries(DemoData.FOLDER_ID, ref(path).parent).first().single { it.ref.path == path }
    }

    private fun downloading(path: String) = entry(path).state is EntryState.Downloading

    private val family = ref("Family archive 1998.mkv")
    private val conference = ref("Conference talk.mp4")
    private val drone = ref("Drone footage raw.mov")

    @Test
    fun demoDataMatchesTheMockups() {
        assertEquals(6_800 * MB, status.pendingBytes)
        assertEquals(14_200 * MB, entry("Series").sizeBytes)
        assertEquals(FileKind.FOLDER, entry("Series").kind)
        assertEquals(Connection.Online(connected = 2, total = 3), status.connection)
    }

    @Test
    fun deselectingAFolderDeletesEveryLocalCopyInsideAndFreesSpace() {
        engine.setKeep(ref("Series"), false)
        assertEquals(EntryState.NotSelected, entry("Series").state)
        assertEquals(0L, entry("Series").localBytes)
        assertEquals(41 * GB + 14_200 * MB, status.freeBytes)
        assertEquals(EntryState.OnDevice, entry("Altai trip 2024.mp4").state)
    }

    @Test
    fun syncDownloadsSelectedFilesInOrderAndEnds() {
        assertTrue(engine.startSync())
        assertTrue(downloading(family.path))
        engine.advance(5_600 * MB)
        assertEquals(EntryState.OnDevice, entry(family.path).state)
        assertTrue(downloading(conference.path))
        engine.advance(1_200 * MB)
        assertNull(status.sync)
        assertEquals(0L, status.pendingBytes)
        // Only the new file uses space; the outdated copy is replaced in place.
        assertEquals(41 * GB - 5_600 * MB, status.freeBytes)
    }

    @Test
    fun stopKeepsPartialDownloads() {
        engine.startSync()
        engine.advance(1_000 * MB)
        engine.stopSync()
        assertNull(status.sync)
        assertEquals(5_800 * MB, status.pendingBytes)
    }

    @Test
    fun syncDoesNotStartOffline() {
        engine.setOffline(true)
        assertFalse(engine.startSync())
        assertNull(status.sync)
        assertEquals(Connection.Disabled, status.connection)
    }

    @Test
    fun resumeWaitsForAConnection() {
        engine.startSync()
        engine.pauseSync()
        engine.setOffline(true)
        assertFalse(engine.resumeSync())
        assertTrue(status.sync!!.paused)
        engine.setOffline(false)
        assertEquals(Connection.Connecting, status.connection)
        assertFalse(engine.resumeSync())
        assertTrue(status.sync!!.paused)
        engine.connectPendingDevices()
        assertTrue(engine.resumeSync())
        assertFalse(status.sync!!.paused)
    }

    @Test
    fun goingOfflinePausesARunningSync() {
        engine.startSync()
        engine.setOffline(true)
        assertTrue(status.sync!!.paused)
    }

    @Test
    fun downloadNowFetchesOnlyThatFile() {
        assertTrue(engine.downloadNow(drone))
        val sync = status.sync!!
        assertEquals(SyncKind.SINGLE_DOWNLOAD, sync.kind)
        assertEquals(1, sync.fileCount)
        assertTrue(downloading(drone.path))
        engine.advance(12_800 * MB)
        assertNull(status.sync)
        assertEquals(EntryState.Waiting(queued = false), entry(family.path).state)
        assertEquals(EntryState.OnDevice, entry(drone.path).state)
    }

    @Test
    fun downloadNowLeavesAPausedSyncPaused() {
        engine.startSync()
        engine.advance(1_000 * MB)
        engine.pauseSync()
        assertTrue(engine.downloadNow(drone))
        assertEquals(SyncKind.SINGLE_DOWNLOAD, status.sync?.kind)
        assertFalse(status.sync!!.paused)
        engine.advance(12_800 * MB)
        // The single download ended; the sync is back, still paused and untouched.
        val sync = status.sync!!
        assertEquals(SyncKind.SYNC, sync.kind)
        assertTrue(sync.paused)
        assertEquals(1_000 * MB, sync.doneBytes)
        assertEquals(EntryState.Downloading(1_000 * MB, 5_600 * MB), entry(family.path).state)
    }

    @Test
    fun aPausedDownloadReplacedByAnotherKeepsWaitingInTheSetAsideSync() {
        val courses = ref("Courses/Kotlin basics.mp4")
        engine.startSync()
        engine.pauseSync()
        engine.downloadNow(drone)
        engine.advance(1_000 * MB)
        engine.pauseSync()
        assertTrue(engine.downloadNow(courses))
        // The paused drone download stays selected with its partial bytes, queued in the paused sync.
        assertTrue(entry(drone.path).keep)
        assertEquals(EntryState.Waiting(queued = true), entry(drone.path).state)
        engine.advance(2_900 * MB)
        val sync = status.sync!!
        assertEquals(SyncKind.SYNC, sync.kind)
        assertTrue(sync.paused)
        assertEquals(3, sync.fileCount)
        assertEquals(1_000 * MB, sync.doneBytes)
    }

    @Test
    fun downloadNowDuringARunningSyncFetchesThatFileFirst() {
        engine.startSync()
        assertTrue(engine.downloadNow(drone))
        assertEquals(SyncKind.SYNC, status.sync?.kind)
        assertTrue(downloading(drone.path))
    }

    @Test
    fun downloadNowChangesNothingWhenItCannotStart() {
        engine.setOffline(true)
        assertFalse(engine.downloadNow(drone))
        assertFalse(entry(drone.path).keep)
        engine.setOffline(false)
        assertFalse(engine.downloadNow(drone)) // still connecting
        assertFalse(entry(drone.path).keep)
        assertFalse(engine.downloadNow(ref("Notes.pdf"))) // already on the device
    }

    @Test
    fun pairingAddsTheDeviceAndItsFolderOffer() {
        engine.addDevice(DemoData.SCANNED_DEVICE_ID)
        assertFalse(status.devices.single { it.id == DemoData.SCANNED_DEVICE_ID }.connected)
        engine.connectPendingDevices()
        assertTrue(status.devices.single { it.id == DemoData.SCANNED_DEVICE_ID }.connected)
        val offer = status.pendingOffers.single()
        assertEquals(DemoData.SCANNED_DEVICE_ID, offer.deviceId)
        engine.acceptFolder(offer)
        assertTrue(status.folders.any { it.id == offer.folderId })
        assertTrue(status.pendingOffers.isEmpty())
    }
}
