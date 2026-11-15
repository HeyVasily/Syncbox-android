package app.avoska.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StatusCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun roundTripsDevicesAndFoldersAndToleratesMissingOrBadFiles() {
        val file = File(tmp.root, "status.json")
        val cache = StatusCache(file)
        assertNull(cache.load())
        val status = CachedStatus(
            thisDeviceId = DemoData.THIS_DEVICE_ID,
            devices = listOf(Device(DemoData.SCANNED_DEVICE_ID, "Test computer", connected = false), Device("X", null, connected = false)),
            folders = listOf(SharedFolder("e2e-media", "Test media", deviceCount = 1)),
        )
        cache.save(status)
        assertEquals(status, StatusCache(file).load())
        file.writeText("[]")
        assertNull(StatusCache(file).load())
    }
}
