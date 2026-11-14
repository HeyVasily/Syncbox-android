package app.avoska.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SizesTest {
    private val en = Locale.US

    @Test
    fun formatsWithOneDecimalDroppedWhenZero() {
        assertEquals("2 MB", formatSize(2_000_000, en))
        assertEquals("14.2 GB", formatSize(14_200_000_000, en))
        assertEquals("41 GB", formatSize(41_000_000_000, en))
        assertEquals("512 B", formatSize(512, en))
        assertEquals("1.5 KB", formatSize(1_500, en))
        assertEquals("0 B", formatSize(0, en))
    }

    @Test
    fun progressUsesTheUnitOfTheTotal() {
        assertEquals("2.3 of 6.8 GB", formatProgress(2_300_000_000, 6_800_000_000, "of", en))
        assertEquals("0.5 of 1.2 GB", formatProgress(500_000_000, 1_200_000_000, "of", en))
    }

    @Test
    fun percentReachesHundredOnlyWhenDone() {
        assertEquals(34, percent(2_300, 6_800))
        assertEquals(99, percent(9_996, 10_000))
        assertEquals(99, percent(9_999, 10_000))
        assertEquals(100, percent(10_000, 10_000))
        assertEquals(0, percent(5, 0))
    }
}
