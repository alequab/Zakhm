package app.zakhm.core

/** The connection to an ELM327-style OBD-II adapter. */
interface ObdTransport {
    fun send(command: String)

    /** Text up to the adapter's `>` prompt, or null if it stopped answering. */
    fun readResponse(): String?
}

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

/** Talks to the adapter. Blocks on the transport, so run it off the main thread. */
class ObdClient(private val transport: ObdTransport) {
    /** Resets the adapter and sets the plain reply format [ObdParser] expects. */
    fun initialize(): Boolean {
        for (command in INIT_COMMANDS) {
            transport.send(command)
            val reply = transport.readResponse() ?: return false
            if (reply.contains('?')) return false
        }
        return true
    }

    /**
     * Asks the car for its speed.
     *
     * @return km/h, or null when the adapter answers but the car doesn't, as when
     *   the ignition is off.
     * @throws java.io.IOException if the adapter stopped answering.
     */
    fun readSpeedKmh(): Int? {
        transport.send(SPEED_REQUEST)
        val reply = transport.readResponse() ?: throw java.io.IOException("Adapter stopped answering")
        return ObdParser.parseSpeedKmh(reply)
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
        const val SPEED_REQUEST = "010D"
        const val VIN_REQUEST = "0902"
    }
}
