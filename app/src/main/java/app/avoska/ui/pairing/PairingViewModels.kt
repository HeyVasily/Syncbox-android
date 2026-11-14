package app.avoska.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.avoska.engine.DemoData
import app.avoska.engine.Device
import app.avoska.engine.DeviceIds
import app.avoska.engine.FolderOffer
import app.avoska.engine.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class AddDeviceUiState(
    val input: String = "",
    val invalid: Boolean = false,
    val scannedDemo: Boolean = false,
)

/** Flow 1, step 1: enter a device ID by scanning or pasting it. */
class AddDeviceViewModel(private val engine: SyncEngine) : ViewModel() {
    private val state = MutableStateFlow(AddDeviceUiState())
    val uiState: StateFlow<AddDeviceUiState> = state.asStateFlow()

    fun onInput(text: String) = state.update { it.copy(input = text, invalid = false) }

    /** There is no camera scanner yet: the demo fills in an example ID instead. */
    fun scan() = state.update { AddDeviceUiState(input = DemoData.SCANNED_DEVICE_ID, scannedDemo = true) }

    fun scanMessageShown() = state.update { it.copy(scannedDemo = false) }

    /** Adds the device and returns its canonical ID, or null if the input is not a valid ID. */
    fun add(): String? {
        val id = DeviceIds.normalize(state.value.input)
        if (id == null) {
            state.update { it.copy(invalid = true) }
        } else {
            engine.addDevice(id)
        }
        return id
    }
}

data class ConnectFolderUiState(val device: Device?, val offers: List<FolderOffer>)

/** Flow 1, steps 2 and 3: wait for the device [deviceId], then accept a folder it offers. */
class ConnectFolderViewModel(private val engine: SyncEngine, private val deviceId: String) : ViewModel() {
    val uiState: StateFlow<ConnectFolderUiState> = engine.status
        .map { status ->
            ConnectFolderUiState(
                device = status.devices.firstOrNull { it.id == deviceId },
                offers = status.pendingOffers.filter { it.deviceId == deviceId },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ConnectFolderUiState(null, emptyList()))

    fun accept(offer: FolderOffer) = engine.acceptFolder(offer)
    fun ignore(offer: FolderOffer) = engine.ignoreFolder(offer)
}
