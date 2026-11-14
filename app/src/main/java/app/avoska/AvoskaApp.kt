package app.avoska

import android.app.Application

class AvoskaApp : Application() {
    val container: AppContainer by lazy { AppContainer() }
}
