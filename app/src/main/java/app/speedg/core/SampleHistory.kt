package app.speedg.core

/** Average of the samples in a time window. */
class WindowStats(
    val count: Int,
    val spanSeconds: Double,
    val meanForce: Vec3,
    val meanGyro: Vec3,
    val meanSpeed: Double,
    val forceStd: Double,
    val gyroStd: Double,
)

/**
 * Ring buffer of recent IMU samples.
 *
 * GPS fixes arrive late, so matching a fix to what the IMU saw at the fix's own
 * timestamp needs the last few seconds of data.
 */
class SampleHistory(private val capacity: Int = 4096) {
    private val times = DoubleArray(capacity)
    private val forces = arrayOfNulls<Vec3>(capacity)
    private val gyros = arrayOfNulls<Vec3>(capacity)
    private val speeds = DoubleArray(capacity)
    private var head = 0
    private var size = 0

    fun add(time: Double, force: Vec3, gyro: Vec3, speed: Double) {
        times[head] = time
        forces[head] = force
        gyros[head] = gyro
        speeds[head] = speed
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    fun clear() {
        head = 0
        size = 0
    }

    private fun indexFromNewest(i: Int) = (head - 1 - i + 2 * capacity) % capacity

    /** The stored speed nearest to [time], or null when no sample is that old. */
    fun speedAt(time: Double): Double? {
        if (size == 0) return null
        for (i in 0 until size) {
            val idx = indexFromNewest(i)
            if (times[idx] <= time) {
                if (i == 0) return speeds[idx]
                val newer = indexFromNewest(i - 1)
                return if (time - times[idx] < times[newer] - time) speeds[idx] else speeds[newer]
            }
        }
        return null
    }

    /** Statistics for samples with `from <= time <= to`, or null when there are none. */
    fun window(from: Double, to: Double): WindowStats? {
        var n = 0
        var force = Vec3.ZERO
        var gyro = Vec3.ZERO
        var speed = 0.0
        var oldest = Double.NaN
        var newest = Double.NaN
        for (i in 0 until size) {
            val idx = indexFromNewest(i)
            val t = times[idx]
            if (t > to) continue
            if (t < from) break
            if (n == 0) newest = t
            oldest = t
            force += forces[idx]!!
            gyro += gyros[idx]!!
            speed += speeds[idx]
            n++
        }
        if (n == 0) return null

        val meanForce = force * (1.0 / n)
        val meanGyro = gyro * (1.0 / n)
        var forceVar = 0.0
        var gyroVar = 0.0
        for (i in 0 until size) {
            val idx = indexFromNewest(i)
            val t = times[idx]
            if (t > to) continue
            if (t < from) break
            val df = forces[idx]!! - meanForce
            val dg = gyros[idx]!! - meanGyro
            forceVar += df dot df
            gyroVar += dg dot dg
        }
        return WindowStats(
            count = n,
            spanSeconds = newest - oldest,
            meanForce = meanForce,
            meanGyro = meanGyro,
            meanSpeed = speed / n,
            forceStd = kotlin.math.sqrt(forceVar / n),
            gyroStd = kotlin.math.sqrt(gyroVar / n),
        )
    }
}
