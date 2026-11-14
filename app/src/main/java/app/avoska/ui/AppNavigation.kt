package app.avoska.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.avoska.AppContainer
import app.avoska.DebugHooks
import app.avoska.engine.EngineStatus
import app.avoska.ui.files.FilesScreen
import app.avoska.ui.files.FilesViewModel
import app.avoska.ui.pairing.AddDeviceScreen
import app.avoska.ui.pairing.AddDeviceViewModel
import app.avoska.ui.pairing.ConnectFolderScreen
import app.avoska.ui.pairing.ConnectFolderViewModel
import app.avoska.ui.settings.DeviceIdScreen
import app.avoska.ui.settings.DeviceIdViewModel
import app.avoska.ui.settings.SettingsScreen
import app.avoska.ui.settings.SettingsViewModel
import app.avoska.ui.settings.SyncNetworkScreen
import app.avoska.ui.settings.SyncNetworkViewModel

/** Navigation keys. Each one is a screen; [route] is its saved form. */
sealed interface Screen {
    val route: String

    /** Files at [dir] ("" is the root) of the shared folder [folderId]. */
    data class Files(val folderId: String, val dir: String = "") : Screen {
        override val route get() = listOf("files", folderId, dir).joinToString(SEPARATOR)
    }

    data object Settings : Screen { override val route = "settings" }
    data object SyncNetwork : Screen { override val route = "sync-network" }
    data object DeviceId : Screen { override val route = "device-id" }

    data class AddDevice(val firstLaunch: Boolean) : Screen {
        override val route get() = listOf("add-device", firstLaunch).joinToString(SEPARATOR)
    }

    data class ConnectFolder(val deviceId: String, val firstLaunch: Boolean) : Screen {
        override val route get() = listOf("connect-folder", deviceId, firstLaunch).joinToString(SEPARATOR)
    }

    companion object {
        private const val SEPARATOR = "\n"

        fun fromRoute(route: String): Screen {
            // At most three parts, so a newline inside a directory name stays in the last one.
            val parts = route.split(SEPARATOR, limit = 3)
            return when (parts[0]) {
                "files" -> Files(parts[1], parts[2])
                "add-device" -> AddDevice(parts[1].toBoolean())
                "connect-folder" -> ConnectFolder(parts[1], parts[2].toBoolean())
                Settings.route -> Settings
                SyncNetwork.route -> SyncNetwork
                DeviceId.route -> DeviceId
                else -> error("Unknown route $route")
            }
        }
    }
}

/** Flow 1: pairing on first launch, when no folder is shared yet; otherwise the first folder. */
fun startStack(status: EngineStatus): List<Screen> =
    status.folders.firstOrNull()?.let { listOf(Screen.Files(it.id)) } ?: listOf(Screen.AddDevice(firstLaunch = true))

private val BackStackSaver: Saver<SnapshotStateList<Screen>, Any> = listSaver(
    save = { stack -> stack.map { it.route } },
    restore = { routes -> mutableStateListOf<Screen>().apply { addAll(routes.map(Screen::fromRoute)) } },
)

/**
 * Navigation 3 with a plain list as the back stack, saved as route strings so it survives
 * configuration changes without kotlinx.serialization.
 */
@Composable
fun AppNavigation(container: AppContainer, startStack: List<Screen>, onExit: () -> Unit) {
    val backStack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf<Screen>().apply { addAll(startStack) } }
    val engine = container.engine
    val settings = container.settings
    fun push(screen: Screen) = backStack.add(screen)
    fun pop() = if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else onExit()
    fun replaceAll(screen: Screen) {
        backStack.clear()
        backStack.add(screen)
    }

    NavDisplay(
        backStack = backStack,
        onBack = { pop() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = { key ->
            NavEntry(key) {
                when (key) {
                    is Screen.Files -> FilesScreen(
                        viewModel = viewModel {
                            FilesViewModel(engine, settings, key.folderId, key.dir).also(DebugHooks::onFilesViewModel)
                        },
                        onBack = { pop() },
                        onOpenFolder = { push(Screen.Files(key.folderId, it)) },
                        onOpenSettings = { push(Screen.Settings) },
                    )
                    Screen.Settings -> SettingsScreen(
                        viewModel = viewModel { SettingsViewModel(engine, settings) },
                        onBack = { pop() },
                        onAddDevice = { push(Screen.AddDevice(firstLaunch = false)) },
                        onSyncNetwork = { push(Screen.SyncNetwork) },
                        onShowDeviceId = { push(Screen.DeviceId) },
                    )
                    Screen.SyncNetwork -> SyncNetworkScreen(viewModel { SyncNetworkViewModel(settings) }, onBack = { pop() })
                    Screen.DeviceId -> DeviceIdScreen(viewModel { DeviceIdViewModel(engine) }, onBack = { pop() })
                    is Screen.AddDevice -> AddDeviceScreen(
                        viewModel = viewModel { AddDeviceViewModel(engine) },
                        firstLaunch = key.firstLaunch,
                        onBack = { pop() },
                        onAdded = { push(Screen.ConnectFolder(it, key.firstLaunch)) },
                        onShowDeviceId = { push(Screen.DeviceId) },
                    )
                    is Screen.ConnectFolder -> ConnectFolderScreen(
                        viewModel = viewModel { ConnectFolderViewModel(engine, key.deviceId) },
                        firstLaunch = key.firstLaunch,
                        onBack = { pop() },
                        onAccepted = { replaceAll(Screen.Files(it)) },
                        onDone = {
                            // Back to where Add device was opened, normally Settings.
                            backStack.removeAll { it is Screen.ConnectFolder || it is Screen.AddDevice }
                        },
                    )
                }
            }
        },
    )
}
