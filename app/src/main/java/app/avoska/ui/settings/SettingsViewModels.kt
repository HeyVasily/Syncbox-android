package app.avoska.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.avoska.AppSettings
import app.avoska.Preferences
import app.avoska.SyncNetwork
import app.avoska.engine.Device
import app.avoska.engine.EngineStatus
import app.avoska.engine.SharedFolder
import app.avoska.engine.SyncEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val STOP_TIMEOUT_MILLIS = 5_000L

private fun build(status: EngineStatus, prefs: Preferences) = SettingsUiState(
    devices = status.devices,
    folders = status.folders,
    syncNetwork = prefs.syncNetwork,
    offlineMode = prefs.offlineMode,
    usedBytes = status.onDeviceBytes,
    freeBytes = status.freeBytes,
)

data class SettingsUiState(
    val devices: List<Device>,
    val folders: List<SharedFolder>,
    val syncNetwork: SyncNetwork,
    val offlineMode: Boolean,
    val usedBytes: Long,
    val freeBytes: Long,
)

class SettingsViewModel(engine: SyncEngine, private val settings: AppSettings) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> = combine(engine.status, settings.preferences, ::build)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            build(engine.status.value, settings.preferences.value),
        )

    fun setOfflineMode(enabled: Boolean) = settings.setOfflineMode(enabled)
}

class SyncNetworkViewModel(private val settings: AppSettings) : ViewModel() {
    val selected: StateFlow<SyncNetwork> = settings.preferences.map { it.syncNetwork }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), settings.preferences.value.syncNetwork)

    fun select(network: SyncNetwork) = settings.setSyncNetwork(network)
}

class DeviceIdViewModel(engine: SyncEngine) : ViewModel() {
    val deviceId: StateFlow<String> = engine.status.map { it.thisDeviceId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), engine.status.value.thisDeviceId)
}
