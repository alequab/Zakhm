package app.speedg

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import app.speedg.core.ObdClient
import java.io.IOException

enum class ObdState { IDLE, CONNECTING, CONNECTED, CAR_NOT_RESPONDING, FAILED }

/**
 * Keeps a Bluetooth link to an ELM327 adapter and polls the car's speed,
 * reconnecting for as long as it runs. Callbacks arrive on its own thread.
 */
class ObdConnection(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val address: String,
    private val onState: (ObdState) -> Unit,
    /** Speed in km/h, stamped with the middle of the request on the elapsedRealtime clock. */
    private val onSpeed: (kmh: Int, nanos: Long) -> Unit,
    private val onVin: (String) -> Unit,
) {
    private enum class LinkType { CLASSIC_SECURE, CLASSIC_INSECURE, BLE }

    @Volatile private var running = false
    @Volatile private var link: ObdLink? = null
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ run() }, "obd").apply { start() }
    }

    fun stop() {
        running = false
        closeLink()
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        // Cheap adapters often accept only one way of connecting, so keep trying each.
        var attempt = 0
        while (running) {
            onState(ObdState.CONNECTING)
            try {
                val device = adapter.getRemoteDevice(address)
                val types = linkTypesFor(device)
                val client = ObdClient(open(device, types[attempt % types.size]))
                if (!client.initialize()) throw IOException("Adapter rejected setup")
                client.readVin()?.let(onVin)
                poll(client)
            } catch (_: IOException) {
                attempt++
                if (running) onState(ObdState.FAILED)
            } catch (_: SecurityException) {
                if (running) onState(ObdState.FAILED)
            } catch (_: IllegalArgumentException) {
                if (running) onState(ObdState.FAILED)
            } catch (_: InterruptedException) {
                return
            } finally {
                closeLink()
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

    @SuppressLint("MissingPermission")
    private fun linkTypesFor(device: BluetoothDevice) = when (device.type) {
        BluetoothDevice.DEVICE_TYPE_LE -> listOf(LinkType.BLE)
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> listOf(LinkType.CLASSIC_SECURE, LinkType.CLASSIC_INSECURE)
        else -> LinkType.entries.toList()
    }

    private fun open(device: BluetoothDevice, type: LinkType): ObdLink = when (type) {
        LinkType.BLE -> BleObdLink(context, device).also {
            link = it
            it.open()
        }
        LinkType.CLASSIC_SECURE, LinkType.CLASSIC_INSECURE ->
            ClassicObdLink(device, secure = type == LinkType.CLASSIC_SECURE).also {
                link = it
                it.open()
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

    private fun closeLink() {
        link?.close()
        link = null
    }

    private companion object {
        const val RETRY_DELAY_MS = 3000L
    }
}
