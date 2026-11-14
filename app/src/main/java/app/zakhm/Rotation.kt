package app.zakhm

import android.content.pm.ActivityInfo

/**
 * How long the phone must stay untouched before the screen follows the mount again. The
 * in-hand signal ends 2 s after the phone stops moving, even while it's still held, so
 * following it straight away would flip the screen back while someone reads it.
 */
internal const val HAND_RELEASE_MS = 5_000L

/**
 * The orientation to ask Android for. A phone taken out of a sideways mount is held upright,
 * so while it's handled the screen turns to portrait, either way up. Back in the mount, the
 * chosen mode applies again; "Auto" follows the phone's tilt even with Android's rotation locked,
 * which a phone lying flat in a hand can't settle, so it keeps portrait until really turned.
 */
internal fun screenOrientation(mode: RotationMode, heldInHand: Boolean): Int = when {
    mode == RotationMode.PORTRAIT || heldInHand -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    mode == RotationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}
