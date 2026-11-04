package app.speedg.core

/** Where speed readings come from. G-force always comes from the phone. */
enum class SpeedInput { PHONE, OBD, ANDROID_AUTO }

/**
 * Decides which readings reach the engine. A selected car source wins while it
 * keeps answering, and GPS takes over whenever it goes quiet.
 */
class SpeedRouter {
    var selected = SpeedInput.PHONE
        private set
    private var lastCarFix = Double.NEGATIVE_INFINITY

    fun select(input: SpeedInput) {
        selected = input
        lastCarFix = Double.NEGATIVE_INFINITY
    }

    /** Records a car reading. Returns true if it comes from the selected source. */
    fun onCarFix(source: SpeedInput, time: Double): Boolean {
        if (source != selected || source == SpeedInput.PHONE) return false
        lastCarFix = time
        return true
    }

    fun active(now: Double): SpeedInput =
        if (selected == SpeedInput.PHONE || now - lastCarFix > CAR_TIMEOUT_SECONDS) SpeedInput.PHONE else selected

    private companion object {
        const val CAR_TIMEOUT_SECONDS = 2.0
    }
}
