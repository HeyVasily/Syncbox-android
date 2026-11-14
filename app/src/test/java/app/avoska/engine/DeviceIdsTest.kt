package app.avoska.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdsTest {
    // The example ID from the Syncthing documentation.
    private val docsExample = "MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD"

    @Test
    fun acceptsValidIdsInAnyCommonSpelling() {
        assertTrue(DeviceIds.isValid(docsExample))
        assertEquals(docsExample, DeviceIds.normalize(docsExample.lowercase()))
        assertEquals(docsExample, DeviceIds.normalize(" " + docsExample.replace("-", "") + "\n"))
        assertTrue(DeviceIds.isValid(DemoData.THIS_DEVICE_ID))
        assertTrue(DeviceIds.isValid(DemoData.SCANNED_DEVICE_ID))
    }

    @Test
    fun rejectsWrongCheckCharactersLengthAndAlphabet() {
        assertNull(DeviceIds.normalize(docsExample.replaceRange(0, 1, "N")))
        assertFalse(DeviceIds.isValid(docsExample.dropLast(1)))
        assertFalse(DeviceIds.isValid(docsExample.replace('B', '8')))
        assertFalse(DeviceIds.isValid(""))
    }
}
