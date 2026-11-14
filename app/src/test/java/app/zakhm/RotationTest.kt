package app.zakhm

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class RotationTest {
    @Test
    fun aHeldPhoneShowsPortraitWhateverTheMode() {
        RotationMode.entries.forEach { mode ->
            assertEquals(mode.name, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, screenOrientation(mode, heldInHand = true))
        }
    }

    @Test
    fun inTheMountEachModeHasItsOwnWay() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, screenOrientation(RotationMode.AUTO, heldInHand = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, screenOrientation(RotationMode.PORTRAIT, heldInHand = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, screenOrientation(RotationMode.LANDSCAPE, heldInHand = false))
    }
}
