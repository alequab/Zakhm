package app.speedg.core

import kotlin.math.sqrt

/** Minimal 3D vector used for all sensor maths. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)

    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    val norm: Double get() = sqrt(this dot this)

    fun normalized(): Vec3 {
        val n = norm
        return if (n > 1e-9) this * (1.0 / n) else this
    }

    /** This vector with its component along the unit vector [axis] removed. */
    fun perpendicularTo(axis: Vec3): Vec3 = this - axis * (this dot axis)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}
