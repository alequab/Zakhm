package app.speedg.car

import app.speedg.core.CarIdentity

/** Passes data from the car screen's session to whatever is listening on the phone side. */
object AndroidAutoSpeed {
    @Volatile
    var listener: ((metersPerSecond: Float) -> Unit)? = null

    @Volatile
    var identityListener: ((CarIdentity) -> Unit)? = null

    /** The connected car, kept because it may report in before the phone screen is open. */
    @Volatile
    var identity: CarIdentity? = null
        private set

    fun publish(metersPerSecond: Float) {
        listener?.invoke(metersPerSecond)
    }

    fun publishIdentity(car: CarIdentity) {
        identity = car
        identityListener?.invoke(car)
    }
}
