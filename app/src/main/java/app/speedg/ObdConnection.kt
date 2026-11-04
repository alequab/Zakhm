package app.speedg

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import app.speedg.core.ObdClient
import app.speedg.core.ObdTransport
import java.io.IOException
import java.util.UUID

enum class ObdState { IDLE, CONNECTING, CONNECTED, CAR_NOT_RESPONDING, FAILED }

/**
 * Keeps a Bluetooth link to a paired ELM327 adapter and polls the car's speed,
 * reconnecting for as long as it runs. Callbacks arrive on its own thread.
 */
class ObdConnection(
    private val adapter: BluetoothAdapter,
    private val address: String,
    private val onState: (ObdState) -> Unit,
    /** Speed in km/h, stamped with the middle of the request on the elapsedRealtime clock. */
    private val onSpeed: (kmh: Int, nanos: Long) -> Unit,
    private val onVin: (String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var socket: BluetoothSocket? = null
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ run() }, "obd").apply { start() }
    }

    fun stop() {
        running = false
        closeSocket()
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        // Cheap adapters often only accept one of the two connection types.
        var insecure = false
        while (running) {
            onState(ObdState.CONNECTING)
            try {
                val connected = connect(insecure)
                socket = connected
                val client = ObdClient(SocketTransport(connected))
                if (!client.initialize()) throw IOException("Adapter rejected setup")
                client.readVin()?.let(onVin)
                poll(client)
            } catch (_: IOException) {
                insecure = !insecure
                if (running) onState(ObdState.FAILED)
            } catch (_: SecurityException) {
                if (running) onState(ObdState.FAILED)
            } finally {
                closeSocket()
            }
            if (running) {
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun poll(client: ObdClient) {
        var reported: ObdState? = null
        while (running) {
            val before = SystemClock.elapsedRealtimeNanos()
            val kmh = client.readSpeedKmh()
            val after = SystemClock.elapsedRealtimeNanos()
            val state = if (kmh == null) ObdState.CAR_NOT_RESPONDING else ObdState.CONNECTED
            if (state != reported) {
                reported = state
                onState(state)
            }
            if (kmh != null) {
                onSpeed(kmh, before + (after - before) / 2)
            } else {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(insecure: Boolean): BluetoothSocket {
        val device = adapter.getRemoteDevice(address)
        val created = if (insecure) {
            device.createInsecureRfcommSocketToServiceRecord(SERIAL_PORT)
        } else {
            device.createRfcommSocketToServiceRecord(SERIAL_PORT)
        }
        socket = created
        created.connect()
        return created
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
            // Already gone.
        }
        socket = null
    }

    private class SocketTransport(private val socket: BluetoothSocket) : ObdTransport {
        override fun send(command: String) {
            socket.outputStream.write("$command\r".toByteArray(Charsets.US_ASCII))
            socket.outputStream.flush()
        }

        override fun readResponse(): String? {
            val input = socket.inputStream
            val text = StringBuilder()
            val deadline = SystemClock.elapsedRealtime() + RESPONSE_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (input.available() == 0) {
                    Thread.sleep(POLL_MS)
                    continue
                }
                val c = input.read()
                if (c < 0) return null
                if (c.toChar() == '>') return text.toString()
                text.append(c.toChar())
            }
            return null
        }
    }

    private companion object {
        val SERIAL_PORT: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val RETRY_DELAY_MS = 3000L
        const val RESPONSE_TIMEOUT_MS = 6000L
        const val POLL_MS = 5L
    }
}
