package app.avoska

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.avoska.ui.AppNavigation
import app.avoska.ui.startStack
import app.avoska.ui.theme.AvoskaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AvoskaApp).container
        val debugStack = if (savedInstanceState == null) DebugHooks.startStack(container, intent) else null
        val start = debugStack ?: startStack(container.engine.status.value)
        setContent {
            AvoskaTheme {
                AppNavigation(container, startStack = start, onExit = ::finish)
            }
        }
    }
}
