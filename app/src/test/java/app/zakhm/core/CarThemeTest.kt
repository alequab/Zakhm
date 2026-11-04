package app.zakhm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarThemeTest {
    private val samples = buildList {
        val steps = listOf(0x00, 0x40, 0x7F, 0xB0, 0xFF)
        for (r in steps) for (g in steps) for (b in steps) add((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
    }

    private fun assertReadable(theme: CarTheme) {
        val p = theme.palette()
        val label = "%08X/%08X/%08X".format(theme.accent, theme.background, theme.panel)
        assertTrue("text on background $label", ThemeColors.contrast(p.primary, p.background) >= 4.5)
        assertTrue("text on panel $label", ThemeColors.contrast(p.primary, p.panel) >= 4.5)
        assertTrue("hint on panel $label", ThemeColors.contrast(p.secondary, p.panel) >= 4.5)
        assertTrue("hint on background $label", ThemeColors.contrast(p.secondary, p.background) >= 4.5)
        assertTrue("accent on panel $label", ThemeColors.contrast(p.accent, p.panel) >= 3.0)
        assertTrue("accent on background $label", ThemeColors.contrast(p.accent, p.background) >= 3.0)
        assertTrue("label on accent $label", ThemeColors.contrast(p.onAccent, p.accent) >= 4.5)
        assertTrue("good on panel $label", ThemeColors.contrast(p.good, p.panel) >= 3.0)
        assertTrue("alert on panel $label", ThemeColors.contrast(p.alert, p.panel) >= 3.0)
    }

    @Test
    fun everyPresetIsReadable() = ThemePreset.entries.forEach { assertReadable(it.theme) }

    @Test
    fun anyColoursAUserCanChooseStayReadable() {
        for (background in samples) {
            for (accent in samples.filterIndexed { i, _ -> i % 7 == 0 }) {
                for (panel in samples.filterIndexed { i, _ -> i % 11 == 0 }) {
                    assertReadable(CarTheme(accent, background, panel))
                }
            }
        }
    }

    @Test
    fun aReadableAccentIsKeptExactly() {
        val p = CarTheme.DEFAULT.palette()
        assertEquals(CarTheme.DEFAULT.accent, p.accent)
        assertEquals(CarTheme.DEFAULT.background, p.background)
        assertEquals(CarTheme.DEFAULT.panel, p.panel)
    }

    @Test
    fun lightAndDarkBackgroundsAreToldApart() {
        assertTrue(ThemePreset.WHITE.theme.palette().isLight)
        assertFalse(ThemePreset.RED.theme.palette().isLight)
    }

    @Test
    fun transparentColoursBecomeSolid() {
        val p = CarTheme(0x00FF0000, 0x00000000, 0x00101010).palette()
        assertEquals(0xFF, (p.background ushr 24))
        assertEquals(0xFF, (p.panel ushr 24))
        assertEquals(0xFF, (p.accent ushr 24))
    }

    @Test
    fun aThemeIsSavedWithItsCarAndOldSavesGetTheDefault() {
        val theme = ThemePreset.BLUE.theme
        val car = CarProfile("a", "Mine", theme = theme)
        val decoded = CarProfileJson.decode(CarProfileJson.encode(listOf(car), "a"))
        assertNotNull(decoded)
        assertEquals(theme, decoded!!.cars.single().theme)

        val old = """{"version":1,"activeId":"a","cars":[{"id":"a","name":"Old","input":"PHONE"}]}"""
        assertEquals(CarTheme.DEFAULT, CarProfileJson.decode(old)!!.cars.single().theme)
    }

    @Test
    fun eachCarKeepsItsOwnTheme() {
        var next = 0
        val registry = CarRegistry(emptyList(), null) { "car${next++}" }
        val first = registry.active.id
        registry.updateActive { it.copy(theme = ThemePreset.RED.theme) }
        registry.add("Second")
        assertEquals("a new car starts from the default", CarTheme.DEFAULT, registry.active.theme)
        registry.select(first)
        assertEquals(ThemePreset.RED.theme, registry.active.theme)
    }
}
