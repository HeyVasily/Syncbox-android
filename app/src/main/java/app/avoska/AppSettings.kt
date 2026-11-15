package app.avoska

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import java.io.File
import java.util.Properties

enum class SyncNetwork { OFF, WIFI, WIFI_AND_MOBILE, ANY }

data class Preferences(
    val syncNetwork: SyncNetwork = SyncNetwork.WIFI,
    val offlineMode: Boolean = false,
)

/**
 * User preferences that outlive the engine: the sync job reads them without starting Syncthing.
 * Kept in [file] (a small properties file) when given, and read in the constructor, so offline
 * mode holds from the first frame after the process is restarted. Without a file (tests, demo
 * scenes) they live in memory only.
 */
class AppSettings(private val file: File? = null) {
    private val mutable = MutableStateFlow(file?.let(::load) ?: Preferences())
    val preferences: StateFlow<Preferences> = mutable.asStateFlow()

    fun setSyncNetwork(network: SyncNetwork) = save(mutable.updateAndGet { it.copy(syncNetwork = network) })
    fun setOfflineMode(enabled: Boolean) = save(mutable.updateAndGet { it.copy(offlineMode = enabled) })

    /** Written synchronously: a few bytes, and a setting must not be lost if the process dies next. */
    @Synchronized
    private fun save(p: Preferences) {
        val target = file ?: return
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".tmp")
            tmp.outputStream().use { out ->
                Properties().apply {
                    setProperty(KEY_NETWORK, p.syncNetwork.name)
                    setProperty(KEY_OFFLINE, p.offlineMode.toString())
                }.store(out, null)
            }
            if (!tmp.renameTo(target)) Log.w(TAG, "settings: could not replace ${target.name}")
        } catch (e: Exception) {
            Log.w(TAG, "settings: not saved", e)
        }
    }

    private companion object {
        const val TAG = "AvoskaSettings"
        const val KEY_NETWORK = "syncNetwork"
        const val KEY_OFFLINE = "offlineMode"

        fun load(file: File): Preferences? = runCatching {
            if (!file.exists()) return null
            val props = Properties().apply { file.inputStream().use(::load) }
            val defaults = Preferences()
            Preferences(
                syncNetwork = props.getProperty(KEY_NETWORK)
                    ?.let { name -> SyncNetwork.entries.firstOrNull { it.name == name } } ?: defaults.syncNetwork,
                offlineMode = props.getProperty(KEY_OFFLINE)?.toBooleanStrictOrNull() ?: defaults.offlineMode,
            )
        }.getOrNull()
    }
}
