package app.avoska.ui.files

import app.avoska.AppSettings
import app.avoska.engine.DemoData
import app.avoska.engine.DemoSyncEngine
import app.avoska.engine.FileRef
import app.avoska.engine.MB
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {
    private val engine = DemoSyncEngine(CoroutineScope(Dispatchers.Unconfined), autoAdvance = false).apply {
        connectPendingDevices()
    }
    private lateinit var viewModel: FilesViewModel
    private val drone = FileRef(DemoData.FOLDER_ID, "Drone footage raw.mov")

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        viewModel = FilesViewModel(engine, AppSettings(), DemoData.FOLDER_ID, dir = "")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun row(name: String) = runBlocking {
        withTimeout(TIMEOUT) { viewModel.uiState.first { s -> s.rows.orEmpty().any { it.entry.name == name } } }
            .rows!!.single { it.entry.name == name }
    }

    @Test
    fun tappingAFileNotOnTheDeviceOpensItWhenTheDownloadEnds() = runBlocking {
        val collector = launch(Dispatchers.Default) { viewModel.uiState.collect {} }
        viewModel.openFile(row("Drone footage raw.mov"))
        assertNull(viewModel.uiState.value.message)
        engine.advance(12_800 * MB)
        val opened = withTimeout(TIMEOUT) { viewModel.uiState.first { it.message != null } }.message
        assertEquals(FilesMessage.Opened("Drone footage raw.mov"), opened)
        collector.cancel()
    }

    @Test
    fun leavingTheScreenCancelsTheOpen() = runBlocking {
        val collector = launch(Dispatchers.Default) { viewModel.uiState.collect {} }
        viewModel.openFile(row("Drone footage raw.mov"))
        viewModel.cancelPendingOpen()
        engine.advance(12_800 * MB)
        withTimeout(TIMEOUT) {
            viewModel.uiState.first { s -> s.rows.orEmpty().any { it.entry.name == "Drone footage raw.mov" && it.entry.localBytes > 0 } }
        }
        assertNull(viewModel.uiState.value.message)
        collector.cancel()
    }

    @Test
    fun stoppingTheTapDownloadEndsThePendingOpenEvenWithASetAsideSync() = runBlocking {
        val collector = launch(Dispatchers.Default) { viewModel.uiState.collect {} }
        engine.startSync()
        engine.pauseSync()
        viewModel.openFile(row("Drone footage raw.mov"))
        engine.stopSync() // stops the tap-download; the paused sync is still there
        assertTrue(engine.status.value.sync!!.paused)
        engine.resumeSync()
        engine.advance(6_800 * MB) // the sync finishes; the drone file was not part of it
        engine.downloadNow(drone)
        engine.advance(12_800 * MB) // downloaded later by another action: must not open now
        withTimeout(TIMEOUT) {
            viewModel.uiState.first { s -> s.rows.orEmpty().any { it.entry.name == "Drone footage raw.mov" && it.entry.localBytes > 0 } }
        }
        assertNull(viewModel.uiState.value.message)
        collector.cancel()
    }

    @Test
    fun rowsAreNullUntilLoaded() {
        val fresh = FilesViewModel(engine, AppSettings(), DemoData.FOLDER_ID, dir = "")
        assertNull(fresh.uiState.value.rows)
        assertNull(fresh.uiState.value.onDeviceBytes)
        assertEquals("Movies", fresh.uiState.value.title)
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
