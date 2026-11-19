package app.zakhm

import android.content.pm.ActivityInfo

/**
 * How long the phone must stay untouched before the screen follows the mount again. The
 * in-hand signal ends 2 s after the phone stops moving, even while it's still held, so
 * following it straight away would flip the screen back while someone reads it.
 */
internal const val HAND_RELEASE_MS = 5_000L

/**
 * The orientation to ask Android for. While the phone is handled the screen follows how it's
 * held, so a phone taken out of a sideways mount and held upright turns to portrait, and one
 * turned sideways in the hand turns straight away (locking portrait here made it wait until the
 * phone had been left alone for several seconds). Back in the mount, the chosen mode applies
 * again; "Auto" follows the phone's tilt even with Android's rotation locked. A phone lying flat
 * gives no tilt, so Android keeps whichever way the screen already is.
 */
internal fun screenOrientation(mode: RotationMode, heldInHand: Boolean): Int = when {
    mode == RotationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    mode == RotationMode.AUTO || heldInHand -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}
