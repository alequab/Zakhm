package app.zakhm

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class RotationTest {
    @Test
    fun aHeldPhoneFollowsHowItIsHeldUnlessPortraitIsChosen() {
        // Locking portrait while held made turning the phone wait until it was left alone.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, screenOrientation(RotationMode.AUTO, heldInHand = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, screenOrientation(RotationMode.LANDSCAPE, heldInHand = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, screenOrientation(RotationMode.PORTRAIT, heldInHand = true))
    }

    @Test
    fun inTheMountEachModeHasItsOwnWay() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, screenOrientation(RotationMode.AUTO, heldInHand = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, screenOrientation(RotationMode.PORTRAIT, heldInHand = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, screenOrientation(RotationMode.LANDSCAPE, heldInHand = false))
    }
}
