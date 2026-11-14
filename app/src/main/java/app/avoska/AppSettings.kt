package app.avoska

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SyncNetwork { OFF, WIFI, WIFI_AND_MOBILE, ANY }

data class Preferences(
    val syncNetwork: SyncNetwork = SyncNetwork.WIFI,
    val offlineMode: Boolean = false,
)

/**
 * User preferences that outlive the engine: the sync job reads them without starting Syncthing.
 * In memory for now; persisted storage comes later.
 */
class AppSettings {
    private val mutable = MutableStateFlow(Preferences())
    val preferences: StateFlow<Preferences> = mutable.asStateFlow()

    fun setSyncNetwork(network: SyncNetwork) = mutable.update { it.copy(syncNetwork = network) }
    fun setOfflineMode(enabled: Boolean) = mutable.update { it.copy(offlineMode = enabled) }
}
