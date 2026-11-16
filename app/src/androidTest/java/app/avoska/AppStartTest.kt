package app.avoska

import android.system.Os
import android.system.OsConstants
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.avoska.engine.Connection
import app.avoska.engine.DeviceIds
import app.avoska.syncthing.bridge.Bridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The installed debug app on a fresh install: real Syncthing through the gomobile bridge, no
 * device added yet. Runs on an emulator in CI (.github/workflows/emulator.yml).
 */
@RunWith(AndroidJUnit4::class)
class AppStartTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunchRunsSyncthingInTheAppProcess() {
        val engine = (compose.activity.application as AvoskaApp).container().engine
        // The first start creates Syncthing's keys and config, which is slow on an emulator.
        compose.waitUntil(timeoutMillis = 120_000) { engine.status.value.connection == Connection.NoDevice }
        val status = engine.status.value
        assertTrue("device ID ${status.thisDeviceId}", DeviceIds.isValid(status.thisDeviceId))
        assertTrue(Bridge.running())
        assertEquals(status.thisDeviceId, Bridge.deviceID())
        compose.onNodeWithText(compose.activity.getString(R.string.add_a_device)).assertIsDisplayed()
    }

    /** The bridge library is mapped from the APK in place, not extracted, at the device's page size. */
    @Test
    fun bridgeLibraryRunsFromTheApk() {
        Bridge.running() // loads libgojni.so
        val info = compose.activity.applicationInfo
        assertFalse("libgojni.so was extracted", File(info.nativeLibraryDir, "libgojni.so").exists())
        val executable = File("/proc/self/maps").readLines().filter { line ->
            val fields = line.trim().split(Regex("\\s+"))
            fields.size >= 6 && fields[1].contains('x') && fields.last() == info.sourceDir
        }
        assertTrue("no executable mapping of ${info.sourceDir}", executable.isNotEmpty())
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        // CI passes the page size the emulator image must have (4096 or 16384).
        InstrumentationRegistry.getArguments().getString("pageSize")?.let {
            assertEquals("page size", it.toLong(), pageSize)
        }
    }
}
