package app.zakhm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ObdTest {
    private class FakeAdapter(private val replies: Map<String, List<String?>>) : ObdTransport {
        val sent = mutableListOf<String>()
        private val used = mutableMapOf<String, Int>()

        override fun send(command: String) {
            sent += command
        }

        override fun readResponse(): String? {
            val command = sent.last()
            val options = replies[command] ?: return "OK"
            val i = used.getOrDefault(command, 0)
            used[command] = i + 1
            return options[minOf(i, options.size - 1)]
        }
    }

    @Test
    fun parsesEveryReplyFormatAdaptersUse() {
        assertEquals(60, ObdParser.parseSpeedKmh("410D3C"))
        assertEquals(60, ObdParser.parseSpeedKmh("41 0D 3C \r"))
        assertEquals(60, ObdParser.parseSpeedKmh("SEARCHING...\r410D3C\r\r"))
        assertEquals(0, ObdParser.parseSpeedKmh("410D00"))
        assertEquals(255, ObdParser.parseSpeedKmh("410dff"))
        assertEquals(60, ObdParser.parseSpeedKmh("410D3C\r410D3C"))
    }

    @Test
    fun repliesWithoutZakhmiveNothing() {
        assertNull(ObdParser.parseSpeedKmh("NO DATA"))
        assertNull(ObdParser.parseSpeedKmh("UNABLE TO CONNECT"))
        assertNull(ObdParser.parseSpeedKmh("?"))
        assertNull(ObdParser.parseSpeedKmh(""))
        assertNull(ObdParser.parseSpeedKmh("410C1AF8"))
    }

    @Test
    fun initializesThenReadsSpeed() {
        val adapter = FakeAdapter(mapOf("ATZ" to listOf("ELM327 v1.5"), "010D" to listOf("410D32")))
        val client = ObdClient(adapter)
        assertTrue(client.initialize())
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0"), adapter.sent)
        assertEquals(50, client.readSpeedKmh())
    }

    @Test
    fun rejectedOrSilentSetupFails() {
        assertFalse(ObdClient(FakeAdapter(mapOf("ATE0" to listOf("?")))).initialize())
        assertFalse(ObdClient(FakeAdapter(mapOf("ATZ" to listOf(null)))).initialize())
    }

    @Test
    fun carNotAnsweringIsDifferentFromAdapterGone() {
        val car = ObdClient(FakeAdapter(mapOf("010D" to listOf("NO DATA"))))
        assertNull(car.readSpeedKmh())

        val gone = ObdClient(FakeAdapter(mapOf("010D" to listOf(null))))
        var threw = false
        try {
            gone.readSpeedKmh()
        } catch (_: IOException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun routerPrefersSelectedCarSourceThenFallsBackToGps() {
        val router = SpeedRouter()
        assertEquals(SpeedInput.PHONE, router.active(10.0))

        router.select(SpeedInput.OBD)
        assertEquals("nothing heard yet", SpeedInput.PHONE, router.active(10.0))

        assertTrue(router.onCarFix(SpeedInput.OBD, 10.0))
        assertEquals(SpeedInput.OBD, router.active(11.0))
        assertEquals("gone quiet", SpeedInput.PHONE, router.active(12.5))

        assertTrue(router.onCarFix(SpeedInput.OBD, 13.0))
        assertEquals("came back", SpeedInput.OBD, router.active(13.1))
    }

    @Test
    fun routerIgnoresSourcesNotSelected() {
        val router = SpeedRouter()
        assertFalse(router.onCarFix(SpeedInput.OBD, 1.0))
        router.select(SpeedInput.ANDROID_AUTO)
        assertFalse(router.onCarFix(SpeedInput.OBD, 1.0))
        assertEquals(SpeedInput.PHONE, router.active(1.0))
    }
}
