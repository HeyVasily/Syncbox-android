package app.avoska

import android.content.Intent
import app.avoska.engine.DemoData
import app.avoska.engine.DemoSyncEngine
import app.avoska.engine.FileRef
import app.avoska.engine.MB
import app.avoska.ui.Screen
import app.avoska.ui.files.FilesTab
import app.avoska.ui.files.FilesViewModel

/**
 * Debug-only scenes: fixed demo states for screenshots, opened with
 * `adb shell am start -n app.avoska/.MainActivity --es scene <name>` (see dev/screenshots.sh).
 * Scenes stop the demo simulation so the state holds still.
 */
object DebugHooks {
    private const val EXTRA = "scene"

    /** Applied to the next Files screen, then cleared. */
    private var filesAction: ((FilesViewModel) -> Unit)? = null

    val scenes = listOf(
        "files", "files-on-device", "files-syncing", "files-menu", "files-remove", "files-offline",
        "settings", "sync-network", "device-id", "add-device", "pairing-waiting", "folder-offer",
    )

    fun startStack(container: AppContainer, intent: Intent): List<Screen>? {
        val name = intent.getStringExtra(EXTRA)?.takeIf { it in scenes } ?: return null
        val engine = container.engine as DemoSyncEngine
        engine.autoAdvance = false
        engine.connectPendingDevices()
        val files = listOf(Screen.Files(DemoData.FOLDER_ID))
        val settings = files + Screen.Settings
        fun ref(path: String) = FileRef(DemoData.FOLDER_ID, path)
        return when (name) {
            "files-on-device" -> files.also { filesAction = { it.selectTab(FilesTab.ON_DEVICE) } }
            "files-syncing" -> files.also {
                engine.startSync()
                engine.advance(2_300 * MB)
            }
            "files-menu" -> files.also { filesAction = { it.showMenu(ref("Conference talk.mp4")) } }
            "files-remove" -> files.also { filesAction = { it.requestRemove(ref("Altai trip 2024.mp4")) } }
            "files-offline" -> files.also { container.settings.setOfflineMode(true) }
            "settings" -> settings
            "sync-network" -> settings + Screen.SyncNetwork
            "device-id" -> settings + Screen.DeviceId
            "add-device" -> listOf(Screen.AddDevice(firstLaunch = true))
            "pairing-waiting", "folder-offer" -> {
                engine.addDevice(DemoData.SCANNED_DEVICE_ID)
                if (name == "folder-offer") engine.connectPendingDevices()
                listOf(Screen.AddDevice(firstLaunch = true), Screen.ConnectFolder(DemoData.SCANNED_DEVICE_ID, firstLaunch = true))
            }
            else -> files
        }
    }

    fun onFilesViewModel(viewModel: FilesViewModel) {
        filesAction?.invoke(viewModel)
        filesAction = null
    }
}
