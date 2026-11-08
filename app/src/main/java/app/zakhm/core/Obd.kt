package app.zakhm.core

/** The connection to an ELM327-style OBD-II adapter. */
interface ObdTransport {
    fun send(command: String)

    /** Text up to the adapter's `>` prompt, or null if it stopped answering. */
    fun readResponse(): String?

    /**
     * One line of a stream the adapter keeps sending, without its line end; [PROMPT]
     * when the adapter shows its prompt instead; null if nothing came in time.
     */
    fun readLine(timeoutMs: Long): String?

    companion object {
        const val PROMPT = ">"
    }
}

/** One message heard on the car's network. */
data class CanFrame(val id: Int, val data: ByteArray)

object ObdParser {
    private val SPEED = Regex("410D([0-9A-F]{2})")

    /** The speed in km/h from a reply to `010D`, or null if the reply holds no speed. */
    fun parseSpeedKmh(response: String): Int? {
        val compact = response.uppercase().filterNot { it.isWhitespace() }
        return SPEED.find(compact)?.groupValues?.get(1)?.toInt(16)
    }

    /**
     * The VIN from a reply to `0902`, or null if the reply holds no valid one.
     *
     * CAN cars answer with a length line then numbered frames (`0:490201...`), and
     * older cars with one `4902nn...` line per frame.
     */
    fun parseVin(response: String): String? {
        val vin = StringBuilder()
        for (rawLine in response.uppercase().split('\r', '\n')) {
            val line = rawLine.filterNot { it.isWhitespace() }
            val frame = line.substringAfter(':')
            if (frame.isEmpty() || !frame.all { it in HEX_DIGITS }) continue
            if (':' !in line && !frame.startsWith(VIN_REPLY)) continue
            val data = if (frame.startsWith(VIN_REPLY)) frame.drop(VIN_REPLY.length + 2) else frame
            data.chunked(2).filter { it.length == 2 }.forEach { vin.append(it.toInt(16).toChar()) }
        }
        val valid = vin.filter { it in VIN_CHARACTERS }
        return if (valid.length >= VIN_LENGTH) valid.substring(0, VIN_LENGTH) else null
    }

    private const val VIN_REPLY = "4902"
    private const val VIN_LENGTH = 17
    private const val HEX_DIGITS = "0123456789ABCDEF"

    /** VINs never use I, O or Q. */
    private const val VIN_CHARACTERS = "0123456789ABCDEFGHJKLMNPRSTUVWXYZ"
}

/**
 * Wheel speeds many Kia and Hyundai cars broadcast about 50 times a second on their
 * own network, without being asked. Layout as published in the open-source opendbc
 * car database: four 14-bit little-endian values, 1/32 km/h each, front left first.
 * Not confirmed for every model, so the app checks it against the standard speed
 * request before trusting it.
 */
object WheelSpeedMessage {
    const val CAN_ID = 0x386
    private const val KMH_PER_STEP = 0.03125
    private const val MAX_KMH = 300.0

    /** Front left, front right, rear left, rear right in km/h, or null when the frame can't be wheel speeds. */
    fun decode(data: ByteArray): DoubleArray? {
        if (data.size < 8) return null
        val wheels = DoubleArray(4) { i ->
            val raw = (data[2 * i].toInt() and 0xFF) or ((data[2 * i + 1].toInt() and 0xFF) shl 8)
            (raw and 0x3FFF) * KMH_PER_STEP
        }
        if (wheels.any { it > MAX_KMH }) return null
        val mean = wheels.average()
        // Real wheels turn at nearly the same speed; a misread message doesn't.
        if (wheels.any { kotlin.math.abs(it - mean) > 0.15 * mean + 3.0 }) return null
        return wheels
    }

    /** The car's speed: the average of all four, which is the speed of its middle even in a turn. */
    fun carSpeedKmh(wheels: DoubleArray) = wheels.average()
}

/** Talks to the adapter. Blocks on the transport, so run it off the main thread. */
class ObdClient(private val transport: ObdTransport) {
    /** Resets the adapter and sets the plain reply format [ObdParser] expects. */
    fun initialize(): Boolean {
        for (command in INIT_COMMANDS) {
            transport.send(command)
            val reply = transport.readResponse() ?: return false
            if (reply.contains('?')) return false
        }
        // Speeds things up where supported. Older clones answer "?", which is fine.
        for (command in OPTIONAL_COMMANDS) {
            transport.send(command)
            transport.readResponse() ?: return false
        }
        return findProtocol()
    }

    /**
     * The first request makes the adapter try each of the car's possible protocols,
     * which can take far longer than an ordinary reply. Asking anything else meanwhile
     * looks like an adapter that stopped answering.
     */
    private fun findProtocol(): Boolean {
        transport.send(PROTOCOL_SEARCH_REQUEST)
        repeat(PROTOCOL_SEARCH_WAITS) {
            if (transport.readResponse() != null) return true
        }
        return false
    }

    /**
     * Whether the request says only one reply is expected. The adapter then answers
     * as soon as the first module replies, instead of waiting in case others do too.
     * Cars where two modules answer speed (engine and gearbox) gain the most.
     */
    var usingReplyCount = true
        private set
    private var shortcutMisses = 0

    /**
     * Asks the car for its speed.
     *
     * @return km/h, or null when the adapter answers but the car doesn't, as when
     *   the ignition is off.
     * @throws java.io.IOException if the adapter stopped answering.
     */
    fun readSpeedKmh(): Int? {
        if (usingReplyCount) {
            val reply = ask(FAST_SPEED_REQUEST)
            if (reply.contains('?')) {
                // The adapter doesn't know the shortcut at all.
                usingReplyCount = false
                return ObdParser.parseSpeedKmh(ask(SPEED_REQUEST))
            }
            val kmh = ObdParser.parseSpeedKmh(reply)
            if (kmh != null) {
                shortcutMisses = 0
                return kmh
            }
            // A car misses a reply now and then, so only give up on the shortcut if the plain request keeps working without it.
            val plain = ObdParser.parseSpeedKmh(ask(SPEED_REQUEST))
            if (plain != null && ++shortcutMisses >= MAX_SHORTCUT_MISSES) usingReplyCount = false
            return plain
        }
        return ObdParser.parseSpeedKmh(ask(SPEED_REQUEST))
    }

    /**
     * Listens, read-only, to everything the adapter can hear for a moment and counts
     * each message. Shows whether the car's own traffic reaches the OBD port at all.
     */
    fun survey(forMs: Long): Map<Int, Int> {
        val seen = sortedMapOf<Int, Int>()
        if (ask("ATH1").contains('?')) return seen
        ask("ATAR")
        transport.send("ATMA")
        try {
            val deadline = System.nanoTime() + forMs * 1_000_000
            while (System.nanoTime() < deadline) {
                val frame = nextFrame(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)) ?: break
                seen[frame.id] = (seen[frame.id] ?: 0) + 1
            }
        } catch (_: java.io.IOException) {
            // Cheap adapters stop when their buffer fills; what was heard is enough.
        }
        stopListening()
        return seen
    }

    /**
     * Starts listening, read-only, to one message on the car's network. The adapter
     * then sends it every time the car does, without being asked.
     *
     * @return false if the adapter can't do this.
     */
    fun startListening(canId: Int): Boolean {
        for (command in listOf("ATH1", "ATCRA %03X".format(canId))) {
            if (ask(command).contains('?')) {
                ask("ATH0")
                return false
            }
        }
        transport.send("ATMA")
        return true
    }

    /**
     * The next message heard, or null if none came in time.
     *
     * @throws java.io.IOException if the adapter stopped listening, as cheap ones do when their buffer fills.
     */
    fun nextFrame(timeoutMs: Long): CanFrame? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return null
            val line = transport.readLine(left) ?: return null
            if (line == ObdTransport.PROMPT) throw java.io.IOException("Adapter stopped listening")
            parseFrame(line)?.let { return it }
        }
    }

    /** Stops listening and goes back to answering requests. */
    fun stopListening() {
        transport.send("")
        // Whatever was still on its way, up to the prompt.
        val until = System.nanoTime() + STOP_WAIT_MS * 1_000_000
        while (System.nanoTime() < until) {
            val line = transport.readLine(STOP_WAIT_MS) ?: break
            if (line == ObdTransport.PROMPT) break
        }
        ask("ATAR")
        ask("ATH0")
    }

    private fun parseFrame(line: String): CanFrame? {
        val hex = line.uppercase().filterNot { it.isWhitespace() }
        if (hex.length < 5 || (hex.length - 3) % 2 != 0 || !hex.all { it in "0123456789ABCDEF" }) return null
        val id = hex.substring(0, 3).toInt(16)
        val data = ByteArray((hex.length - 3) / 2) { i -> hex.substring(3 + 2 * i, 5 + 2 * i).toInt(16).toByte() }
        return CanFrame(id, data)
    }

    private fun ask(command: String): String {
        transport.send(command)
        return transport.readResponse() ?: throw java.io.IOException("Adapter stopped answering")
    }

    /**
     * Asks the car for its VIN. Null when it doesn't share one, which some older cars don't.
     *
     * @throws java.io.IOException if the adapter stopped answering.
     */
    fun readVin(): String? {
        transport.send(VIN_REQUEST)
        val reply = transport.readResponse() ?: throw java.io.IOException("Adapter stopped answering")
        return ObdParser.parseVin(reply)
    }

    private companion object {
        val INIT_COMMANDS = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")
        /**
         * Adaptive timing: waits about as long as this car usually takes to answer. The
         * aggressive setting (ATAT2) cut a Picanto off mid-reply, giving NO DATA every other request.
         */
        val OPTIONAL_COMMANDS = listOf("ATAT1")
        const val PROTOCOL_SEARCH_REQUEST = "0100"

        /** Each wait is one reply timeout (6 s), so about 24 s, close to the 25 s Fahis allows. */
        const val PROTOCOL_SEARCH_WAITS = 4
        const val MAX_SHORTCUT_MISSES = 5
        const val SPEED_REQUEST = "010D"
        const val FAST_SPEED_REQUEST = "010D1"
        const val STOP_WAIT_MS = 500L
        const val VIN_REQUEST = "0902"
    }
}
