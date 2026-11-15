package app.avoska

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppSettingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun settingsSurviveARestartAndBadFilesFallBackToDefaults() {
        val file = File(tmp.root, "settings.properties")
        assertEquals(Preferences(), AppSettings(file).preferences.value)
        AppSettings(file).apply {
            setOfflineMode(true)
            setSyncNetwork(SyncNetwork.ANY)
        }
        assertEquals(Preferences(SyncNetwork.ANY, offlineMode = true), AppSettings(file).preferences.value)
        file.writeText("syncNetwork=NOPE\nofflineMode=maybe\n")
        assertEquals(Preferences(), AppSettings(file).preferences.value)
    }
}
