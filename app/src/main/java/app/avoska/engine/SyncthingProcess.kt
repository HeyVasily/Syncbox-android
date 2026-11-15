package app.avoska.engine

import app.avoska.syncthing.bridge.Bridge
import java.io.File

/** A running Syncthing: where its REST API listens and who it is. */
data class SyncthingSession(val apiAddress: String, val apiKey: String, val deviceId: String)

/** Starts and stops Syncthing. Blocking calls; one instance per process. */
interface SyncthingProcess {
    /** Starts Syncthing with the download gate closed. Throws if it cannot start. */
    fun start(home: File): SyncthingSession

    fun stop()

    /** False once Syncthing has stopped, also when it stopped by itself; [failure] then says why. */
    fun isRunning(): Boolean

    fun failure(): String
}

/** The real Syncthing, in this process through the gomobile bridge (engine/bridge). */
object BridgeProcess : SyncthingProcess {
    override fun start(home: File): SyncthingSession {
        // Only a download job may open the gate; there is none yet.
        Bridge.start(home.absolutePath, false)
        return SyncthingSession(Bridge.apiAddress(), Bridge.apiKey(), Bridge.deviceID())
    }

    override fun stop() = Bridge.stop()

    override fun isRunning(): Boolean = Bridge.running()

    override fun failure(): String = Bridge.failure()
}
