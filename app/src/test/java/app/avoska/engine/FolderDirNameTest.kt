package app.avoska.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderDirNameTest {
    @Test
    fun idsThatReadTheSameGetTheirOwnDirectory() {
        val ids = listOf("media", ".media", "a/b", "a_b", "a b", "..", "", "\u0424\u043e\u0442\u043e")
        val names = ids.map(::folderDirName)
        assertEquals(ids.size, names.toSet().size)
        names.forEach { assertTrue(it, it.matches(Regex("[A-Za-z0-9_\\-\\p{L}]+-[0-9a-f]{12}")) && !it.startsWith(".")) }
        assertEquals(folderDirName("media"), folderDirName("media"))
    }
}
