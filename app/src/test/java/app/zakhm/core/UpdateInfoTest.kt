package app.zakhm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateInfoTest {
    private val sha = "a".repeat(64)
    private fun answer(code: String = "9", size: String = "9000000", hash: String = "\"$sha\"") =
        """{"versionCode":$code,"versionName":"1.4.1","notesAr":"ملاحظات","notesEn":"Notes","sha256":$hash,"size":$size}"""

    @Test
    fun readsARelease() {
        val update = UpdateInfo.parse(answer(), arabic = false)!!
        assertEquals(9, update.versionCode)
        assertEquals("1.4.1", update.versionName)
        assertEquals("Notes", update.notes)
        assertEquals(sha, update.sha256)
        assertEquals(9000000L, update.size)
    }

    @Test
    fun picksNotesInTheAppsLanguage() {
        assertEquals("ملاحظات", UpdateInfo.parse(answer(), arabic = true)!!.notes)
    }

    @Test
    fun missingNotesAreFine() {
        val text = """{"versionCode":9,"versionName":"1.4.1","sha256":"$sha","size":100}"""
        assertEquals("", UpdateInfo.parse(text, arabic = false)!!.notes)
    }

    @Test
    fun acceptsUppercaseHash() {
        assertEquals(sha, UpdateInfo.parse(answer(hash = "\"${sha.uppercase()}\""), arabic = false)!!.sha256)
    }

    @Test
    fun badAnswersOfferNothing() {
        assertNull(UpdateInfo.parse("not json", arabic = false))
        assertNull(UpdateInfo.parse("""{"versionCode":0}""", arabic = false))
        assertNull(UpdateInfo.parse(answer(code = "0"), arabic = false))
        assertNull(UpdateInfo.parse(answer(size = "0"), arabic = false))
        assertNull("absurdly large file", UpdateInfo.parse(answer(size = "999999999999"), arabic = false))
        assertNull("hash too short", UpdateInfo.parse(answer(hash = "\"abc\""), arabic = false))
        assertNull("hash not hex", UpdateInfo.parse(answer(hash = "\"${"z".repeat(64)}\""), arabic = false))
    }
}
