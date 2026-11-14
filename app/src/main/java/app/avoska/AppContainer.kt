package app.avoska

import app.avoska.engine.DemoSyncEngine
import app.avoska.engine.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Manual dependency injection: the app's single object graph. */
class AppContainer {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = AppSettings()
    val engine: SyncEngine = DemoSyncEngine(appScope)

    init {
        // Offline mode is a preference; the engine only follows it.
        appScope.launch {
            settings.preferences.map { it.offlineMode }.distinctUntilChanged().collect(engine::setOffline)
        }
    }
}
