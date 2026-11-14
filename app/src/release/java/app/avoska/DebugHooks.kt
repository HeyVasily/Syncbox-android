package app.avoska

import android.content.Intent
import app.avoska.ui.Screen
import app.avoska.ui.files.FilesViewModel

/** Release builds have no debug scenes. The debug source set has the real implementation. */
object DebugHooks {
    @Suppress("UNUSED_PARAMETER")
    fun startStack(container: AppContainer, intent: Intent): List<Screen>? = null

    @Suppress("UNUSED_PARAMETER")
    fun onFilesViewModel(viewModel: FilesViewModel) = Unit
}
