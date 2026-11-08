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

        /** What a listening adapter streams, one line at a time. */
        val streamed = ArrayDeque<String>()

        override fun readLine(timeoutMs: Long): String? = streamed.removeFirstOrNull()
    }

    /** A wheel-speed message as the adapter shows it with headers on and spaces off. */
    private fun wheelLine(fl: Double, fr: Double, rl: Double, rr: Double): String {
        val sb = StringBuilder("386")
        for (kmh in listOf(fl, fr, rl, rr)) {
            val raw = (kmh / 0.03125).toInt() or 0x8000 // A counter in the top bits must be ignored.
            sb.append("%02X%02X".format(raw and 0xFF, (raw shr 8) and 0xFF))
        }
        return sb.toString()
    }

    @Test
    fun decodesBroadcastWheelSpeeds() {
        val adapter = FakeAdapter(emptyMap())
        adapter.streamed += listOf("SEARCHING...", wheelLine(50.0, 50.5, 49.75, 50.25), "BUFFER FULL")
        val client = ObdClient(adapter)
        assertTrue(client.startListening(WheelSpeedMessage.CAN_ID))
        assertEquals(listOf("ATH1", "ATCRA 386", "ATMA"), adapter.sent)
        val frame = client.nextFrame(100)!!
        assertEquals(0x386, frame.id)
        val wheels = WheelSpeedMessage.decode(frame.data)!!
        assertEquals(50.125, WheelSpeedMessage.carSpeedKmh(wheels), 0.05)
        assertNull("lines that aren't messages are skipped until time runs out", client.nextFrame(100))
    }

    @Test
    fun wheelSpeedsThatDisagreeAreNotTrusted() {
        val bytes = { line: String -> ByteArray((line.length - 3) / 2) { line.substring(3 + 2 * it, 5 + 2 * it).toInt(16).toByte() } }
        assertNull(WheelSpeedMessage.decode(bytes(wheelLine(50.0, 50.0, 50.0, 120.0))))
        assertTrue(WheelSpeedMessage.decode(bytes(wheelLine(0.0, 0.0, 0.0, 0.0))) != null)
        assertNull(WheelSpeedMessage.decode(ByteArray(4)))
    }

    @Test
    fun anAdapterThatStopsListeningIsNoticed() {
        val adapter = FakeAdapter(emptyMap())
        adapter.streamed += ObdTransport.PROMPT
        val client = ObdClient(adapter)
        client.startListening(WheelSpeedMessage.CAN_ID)
        var threw = false
        try {
            client.nextFrame(100)
        } catch (_: IOException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun anAdapterThatCannotFilterIsNotAskedToListen() {
        val adapter = FakeAdapter(mapOf("ATCRA 386" to listOf("?")))
        assertFalse(ObdClient(adapter).startListening(WheelSpeedMessage.CAN_ID))
        assertTrue("ATMA" !in adapter.sent)
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
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATAT1", "0100"), adapter.sent)
        assertEquals(50, client.readSpeedKmh())
    }

    @Test
    fun asksForOneReplyWhenTheAdapterUnderstands() {
        val adapter = FakeAdapter(mapOf("010D1" to listOf("410D32")))
        val client = ObdClient(adapter)
        assertEquals(50, client.readSpeedKmh())
        assertEquals(50, client.readSpeedKmh())
        assertEquals(listOf("010D1", "010D1"), adapter.sent)
        assertTrue(client.usingReplyCount)
    }

    @Test
    fun fallsBackToThePlainRequestWhenTheShortcutIsRefused() {
        val adapter = FakeAdapter(mapOf("010D1" to listOf("?"), "010D" to listOf("410D32")))
        val client = ObdClient(adapter)
        assertEquals(50, client.readSpeedKmh())
        assertFalse(client.usingReplyCount)
        assertEquals(50, client.readSpeedKmh())
        assertEquals(listOf("010D1", "010D", "010D"), adapter.sent)
    }

    @Test
    fun aCarThatIsOffDoesNotTurnTheShortcutOff() {
        val client = ObdClient(FakeAdapter(mapOf("010D1" to listOf("NO DATA"), "010D" to listOf("NO DATA"))))
        assertNull(client.readSpeedKmh())
        assertTrue(client.usingReplyCount)
    }

    @Test
    fun anAdapterWithoutAdaptiveTimingStillWorks() {
        assertTrue(ObdClient(FakeAdapter(mapOf("ATAT1" to listOf("?")))).initialize())
    }

    @Test
    fun aSlowProtocolSearchIsWaitedFor() {
        // The first two waits run out while the adapter is still trying protocols.
        val adapter = FakeAdapter(mapOf("0100" to listOf(null, null, "SEARCHING...\r4100BE3FA813")))
        assertTrue(ObdClient(adapter).initialize())
        assertFalse(ObdClient(FakeAdapter(mapOf("0100" to listOf(null)))).initialize())
    }

    @Test
    fun anOccasionalMissDoesNotDropTheShortcut() {
        val adapter = FakeAdapter(mapOf("010D1" to listOf("410D32", "NO DATA", "410D33"), "010D" to listOf("410D32")))
        val client = ObdClient(adapter)
        repeat(3) { client.readSpeedKmh() }
        assertTrue(client.usingReplyCount)
    }

    @Test
    fun surveyCountsEachMessageHeard() {
        val adapter = FakeAdapter(emptyMap())
        adapter.streamed += listOf("3861122334455667788", "5A0AABB", "3861122334455667788", "BUFFER FULL")
        val heard = ObdClient(adapter).survey(100)
        assertEquals(mapOf(0x386 to 2, 0x5A0 to 1), heard)
        assertTrue(adapter.sent.containsAll(listOf("ATH1", "ATMA", "ATAR", "ATH0")))
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
