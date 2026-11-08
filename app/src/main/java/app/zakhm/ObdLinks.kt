package app.zakhm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.SystemClock
import app.zakhm.core.ObdTransport
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** An open connection to an adapter. */
interface ObdLink : ObdTransport {
    fun close()
}

private const val RESPONSE_TIMEOUT_MS = 6000L
private const val POLL_MS = 5L

/** A classic Bluetooth adapter, which appears as a serial port. */
@SuppressLint("MissingPermission")
class ClassicObdLink(device: BluetoothDevice, secure: Boolean) : ObdLink {
    private val socket: BluetoothSocket = if (secure) {
        device.createRfcommSocketToServiceRecord(SERIAL_PORT)
    } else {
        device.createInsecureRfcommSocketToServiceRecord(SERIAL_PORT)
    }

    fun open() {
        socket.connect()
    }

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

    override fun readLine(timeoutMs: Long): String? {
        val input = socket.inputStream
        val text = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (input.available() == 0) {
                Thread.sleep(1)
                continue
            }
            val c = input.read()
            if (c < 0) return null
            when (c.toChar()) {
                '>' -> return ObdTransport.PROMPT
                '\r', '\n' -> if (text.isNotEmpty()) return text.toString()
                else -> text.append(c.toChar())
            }
        }
        return null
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
            // Already gone.
        }
    }

    private companion object {
        val SERIAL_PORT: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/**
 * A Bluetooth LE adapter. These have no standard serial service, so this looks
 * for a service that can both send and receive, trying the common ones first.
 */
@SuppressLint("MissingPermission")
class BleObdLink(private val context: Context, private val device: BluetoothDevice) : ObdLink {
    private val incoming = LinkedBlockingQueue<Byte>()
    private val ready = CountDownLatch(1)

    @Volatile private var failed = false
    @Volatile private var closed = false
    @Volatile private var writer: BluetoothGattCharacteristic? = null
    @Volatile private var notifier: BluetoothGattCharacteristic? = null
    @Volatile private var notificationsRequested = false
    @Volatile private var gatt: BluetoothGatt? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Talks every 7.5 to 15 ms instead of Android's usual 30 to 50, which is most of each request's wait.
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail()
            val (write, notify) = findSerialCharacteristics(g) ?: return fail()
            writer = write
            notifier = notify
            // Bigger packets carry a reply in one piece. The answer arrives in onMtuChanged.
            if (!g.requestMtu(WANTED_MTU)) enableNotifications(g, notify)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            notifier?.let { enableNotifications(g, it) }
        }

        private fun enableNotifications(g: BluetoothGatt, notify: BluetoothGattCharacteristic) {
            if (notificationsRequested) return
            notificationsRequested = true
            g.setCharacteristicNotification(notify, true)
            val descriptor = notify.getDescriptor(CLIENT_CONFIG) ?: return ready.countDown()
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, value)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) ready.countDown() else fail()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            value.forEach(incoming::add)
        }

        // Android 12 and older call this version instead.
        @Deprecated("Required on older Android versions")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.forEach(incoming::add)
        }
    }

    fun open() {
        // Asks for Bluetooth 5's faster radio mode; most adapters keep the standard one, which is fine.
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_2M_MASK)
            ?: throw IOException("Bluetooth is off")
        val finished = ready.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished || failed) throw IOException("Could not set up the Bluetooth LE adapter")
    }

    private fun fail() {
        failed = true
        ready.countDown()
    }

    override fun send(command: String) {
        val g = gatt ?: throw IOException("Not connected")
        val characteristic = writer ?: throw IOException("Not connected")
        val bytes = "$command\r".toByteArray(Charsets.US_ASCII)
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        // The radio is briefly busy after any write, which isn't a failure.
        repeat(WRITE_ATTEMPTS) {
            if (write(g, characteristic, bytes, type)) return
            Thread.sleep(WRITE_RETRY_MS)
        }
        throw IOException("Adapter refused the command")
    }

    private fun write(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = type
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }

    override fun readResponse(): String? {
        val text = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + RESPONSE_TIMEOUT_MS
        while (!closed && !failed) {
            val wait = deadline - SystemClock.elapsedRealtime()
            if (wait <= 0) return null
            val b = incoming.poll(wait, TimeUnit.MILLISECONDS) ?: return null
            val c = (b.toInt() and 0xFF).toChar()
            if (c == '>') return text.toString()
            text.append(c)
        }
        return null
    }

    override fun readLine(timeoutMs: Long): String? {
        val text = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!closed && !failed) {
            val wait = deadline - SystemClock.elapsedRealtime()
            if (wait <= 0) return null
            val b = incoming.poll(wait, TimeUnit.MILLISECONDS) ?: return null
            when (val c = (b.toInt() and 0xFF).toChar()) {
                '>' -> return ObdTransport.PROMPT
                '\r', '\n' -> if (text.isNotEmpty()) return text.toString()
                else -> text.append(c)
            }
        }
        return null
    }

    /** Called from both the connection's thread and whichever thread stops it. */
    @Synchronized
    override fun close() {
        closed = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private fun findSerialCharacteristics(
        g: BluetoothGatt,
    ): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val canWrite = BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        val canNotify = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE
        val services = g.services
            .filter { it.uuid !in STANDARD_SERVICES }
            .sortedBy { service -> COMMON_SERIAL_SERVICES.indexOf(service.uuid).let { if (it < 0) Int.MAX_VALUE else it } }
        for (service in services) {
            val write = service.characteristics.firstOrNull { it.properties and canWrite != 0 } ?: continue
            val notify = service.characteristics.firstOrNull { it.properties and canNotify != 0 } ?: continue
            return write to notify
        }
        return null
    }

    private companion object {
        /** Android's own limit for a direct connection, so a slow adapter isn't cut off early. */
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val WRITE_ATTEMPTS = 5
        const val WRITE_RETRY_MS = 20L
        const val WANTED_MTU = 247
        val CLIENT_CONFIG: UUID = bluetoothUuid("2902")
        val COMMON_SERIAL_SERVICES = listOf(
            bluetoothUuid("FFE0"),
            bluetoothUuid("FFF0"),
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
        )
        val STANDARD_SERVICES = setOf(bluetoothUuid("1800"), bluetoothUuid("1801"), bluetoothUuid("180A"), bluetoothUuid("180F"))

        fun bluetoothUuid(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805F9B34FB")
    }
}
