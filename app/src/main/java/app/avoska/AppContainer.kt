package app.avoska

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.avoska.engine.BridgeProcess
import app.avoska.engine.DemoSyncEngine
import app.avoska.engine.RestSyncEngine
import app.avoska.engine.SelectionStore
import app.avoska.engine.StatusCache
import app.avoska.engine.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual dependency injection: the app's single object graph. [demo] swaps the real engine for
 * the in-memory demo, which only debug screenshot scenes ask for.
 */
class AppContainer(context: Context, demo: Boolean) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Demo scenes start from defaults every time; the real app keeps the user's settings.
    val settings = if (demo) AppSettings() else AppSettings(File(context.filesDir, "settings.properties"))
    val engine: SyncEngine = if (demo) DemoSyncEngine(appScope) else realEngine(context.filesDir)

    init {
        // Offline mode is a preference; the engine only follows it. The stored value is applied
        // before the app can become visible, so Syncthing never starts in offline mode.
        engine.setOffline(settings.preferences.value.offlineMode)
        appScope.launch {
            settings.preferences.map { it.offlineMode }.distinctUntilChanged().collect(engine::setOffline)
        }
        // Syncthing runs while any activity is visible; the process lifecycle ignores rotation.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = engine.setAppVisible(true)
                override fun onStop(owner: LifecycleOwner) = engine.setAppVisible(false)
            },
        )
    }

    private fun realEngine(files: File) = RestSyncEngine(
        scope = appScope,
        process = BridgeProcess,
        home = File(files, "syncthing"),
        dataRoot = File(files, "folders"),
        selection = SelectionStore(File(files, "selection.json")),
        cache = StatusCache(File(files, "status.json")),
    )
}
