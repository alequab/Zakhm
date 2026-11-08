package app.zakhm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import app.zakhm.core.ObdClient
import app.zakhm.core.WheelSpeedMessage
import java.io.IOException
import kotlin.math.abs

enum class ObdState { IDLE, CONNECTING, CONNECTED, CAR_NOT_RESPONDING, FAILED, DISCONNECTED }

/**
 * Keeps a Bluetooth link to an ELM327 adapter and reads the car's speed,
 * reconnecting for as long as it runs. Callbacks arrive on its own thread.
 */
class ObdConnection(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val address: String,
    private val onState: (ObdState) -> Unit,
    /** Speed in km/h, stamped with the middle of the request (or the moment heard) on the elapsedRealtime clock. */
    private val onSpeed: (kmh: Double, nanos: Long, reading: ObdReading) -> Unit,
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
            // After a failure, keep saying so during retries instead of flicking back to "connecting".
            if (attempt == 0) onState(ObdState.CONNECTING)
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
            } catch (_: RuntimeException) {
                // Android's Bluetooth code can throw almost anything, and this thread must not take the app down.
                attempt++
                if (running) onState(ObdState.FAILED)
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

    private var reported: ObdState? = null

    /** How far the car's broadcast wheel speeds have been checked against the standard request. */
    private enum class Stream { UNTRIED, UNCONFIRMED, CONFIRMED, UNAVAILABLE }

    private var stream = Stream.UNTRIED
    private var streamMatches = 0
    private var streamMismatches = 0
    private var silentListens = 0
    private var lastRequested: Double? = null
    private var surveyed = false
    private var misses = 0

    private fun poll(client: ObdClient) {
        reported = null
        while (running) {
            // Once per connection, after the car answers: can the port hear the car's own messages at all?
            if (!surveyed && reported == ObdState.CONNECTED) {
                surveyed = true
                if (WheelSpeedMessage.CAN_ID !in client.survey(SURVEY_MS)) stream = Stream.UNAVAILABLE
                continue
            }
            when (stream) {
                // Asking keeps the speed coming while the stream is checked, a moment at a time.
                Stream.UNTRIED, Stream.UNCONFIRMED -> {
                    request(client, CHECK_EVERY_MS)
                    if (running) listen(client, PROBE_MS, useForSpeed = false)
                }
                Stream.CONFIRMED -> {
                    listen(client, STREAM_WINDOW_MS, useForSpeed = true)
                    if (running) request(client, 0)
                }
                Stream.UNAVAILABLE -> request(client, Long.MAX_VALUE)
            }
        }
    }

    /** Asks for the speed again and again for [forMs], at least once. Returns the last answer. */
    private fun request(client: ObdClient, forMs: Long): Double? {
        val until = SystemClock.elapsedRealtime() + forMs
        var last: Double? = null
        do {
            val before = SystemClock.elapsedRealtimeNanos()
            val kmh = client.readSpeedKmh()
            val after = SystemClock.elapsedRealtimeNanos()
            if (kmh == null) {
                // One missed reply is normal; several in a row mean the car isn't answering, as with the ignition off.
                if (++misses >= MISSES_BEFORE_PAUSE) {
                    report(ObdState.CAR_NOT_RESPONDING)
                    Thread.sleep(RETRY_DELAY_MS)
                }
                continue
            }
            misses = 0
            report(ObdState.CONNECTED)
            last = kmh.toDouble()
            val reading = ObdReading(ObdReading.Kind.REQUEST, (after - before) / 1_000_000, client.usingReplyCount)
            onSpeed(kmh.toDouble(), before + (after - before) / 2, reading)
        } while (running && SystemClock.elapsedRealtime() < until)
        lastRequested = last
        return last
    }

    /**
     * Listens to the car's broadcast wheel speeds for [forMs]. Until they've matched
     * the requested speed a few times, they're only compared, never shown.
     */
    private fun listen(client: ObdClient, forMs: Long, useForSpeed: Boolean) {
        val before = lastRequested
        if (!client.startListening(WheelSpeedMessage.CAN_ID)) {
            stream = Stream.UNAVAILABLE
            return
        }
        var decoded = 0
        var lastSpeed: Double? = null
        var lastSent = 0L
        val started = SystemClock.elapsedRealtime()
        try {
            while (running && SystemClock.elapsedRealtime() - started < forMs) {
                val frame = client.nextFrame(FRAME_TIMEOUT_MS) ?: break
                val nanos = SystemClock.elapsedRealtimeNanos()
                val wheels = WheelSpeedMessage.decode(frame.data) ?: continue
                decoded++
                val kmh = WheelSpeedMessage.carSpeedKmh(wheels)
                lastSpeed = kmh
                // 50 a second is far more than needed, so pass on every few.
                if (useForSpeed && nanos - lastSent >= STREAM_SPACING_NANOS) {
                    lastSent = nanos
                    report(ObdState.CONNECTED)
                    onSpeed(kmh, nanos, ObdReading(ObdReading.Kind.STREAM, 0, false, wheels))
                }
            }
        } catch (_: IOException) {
            // Cheap adapters stop listening when their buffer fills; asking takes over below.
        }
        client.stopListening()
        if (decoded == 0) {
            // Not heard where the adapter is plugged in, or a different layout on this car.
            if (++silentListens >= MAX_SILENT_LISTENS) stream = Stream.UNAVAILABLE
            return
        }
        silentListens = 0
        if (stream == Stream.UNTRIED) stream = Stream.UNCONFIRMED
        val after = request(client, 0)
        checkStream(lastSpeed, before, after)
    }

    /** Compares the stream with the standard speed taken just before and after it. */
    private fun checkStream(streamed: Double?, before: Double?, after: Double?) {
        if (streamed == null || before == null || after == null) return
        val requested = (before + after) / 2
        // Only meaningful while moving at a steady speed, where both should agree closely.
        if (requested < MIN_CHECK_KMH || abs(before - after) > MAX_CHANGE_KMH) return
        val agrees = abs(streamed - requested) <= maxOf(3.0, requested * 0.06)
        if (agrees) {
            streamMismatches = 0
            if (stream == Stream.UNCONFIRMED && ++streamMatches >= MATCHES_NEEDED) stream = Stream.CONFIRMED
        } else if (++streamMismatches >= MAX_MISMATCHES) {
            stream = Stream.UNAVAILABLE
        }
    }

    private fun report(state: ObdState) {
        if (state != reported) {
            reported = state
            onState(state)
        }
    }

    private fun closeLink() {
        link?.close()
        link = null
    }

    private companion object {
        const val RETRY_DELAY_MS = 3000L

        /** How long to keep asking between short tries of the stream while it's unconfirmed. */
        const val CHECK_EVERY_MS = 8_000L

        /** Short, so the gap in asked-for speeds stays under the 2 s before GPS takes over. */
        const val PROBE_MS = 1_000L
        const val STREAM_WINDOW_MS = 30_000L
        const val FRAME_TIMEOUT_MS = 1_000L
        const val STREAM_SPACING_NANOS = 50_000_000L
        const val MIN_CHECK_KMH = 15.0
        const val MAX_CHANGE_KMH = 4.0
        const val MATCHES_NEEDED = 3
        const val MAX_MISMATCHES = 2
        const val MAX_SILENT_LISTENS = 3
        const val MISSES_BEFORE_PAUSE = 3
        const val SURVEY_MS = 2_000L
    }
}

/** How one OBD speed arrived. */
data class ObdReading(
    val kind: Kind,
    /** Round trip of the request in ms, 0 for the stream. */
    val roundTripMs: Long,
    val replyCountHint: Boolean,
    val wheels: DoubleArray? = null,
) {
    enum class Kind { REQUEST, STREAM }
}
