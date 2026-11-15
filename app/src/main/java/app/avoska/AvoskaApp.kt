package app.avoska

import android.app.Application

class AvoskaApp : Application() {
    private var created: AppContainer? = null

    /**
     * The object graph, created once on first use; [demo] only matters then (see [AppContainer]).
     * Synchronized rather than `by lazy`, which cannot take [demo]: two threads never build two
     * graphs (two engines would each start Syncthing).
     */
    @Synchronized
    fun container(demo: Boolean = false): AppContainer = created ?: AppContainer(this, demo).also { created = it }
}
