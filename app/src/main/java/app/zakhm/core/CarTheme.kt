package app.zakhm.core

import kotlin.math.pow

/** The colours a user chooses for a car, as 0xAARRGGBB. Everything else is worked out from these. */
data class CarTheme(val accent: Int, val background: Int, val panel: Int) {
    companion object {
        val DEFAULT = CarTheme(
            accent = 0xFFFFB020.toInt(),
            background = 0xFF07090D.toInt(),
            panel = 0xFF12161D.toInt(),
        )
    }
}

/** Ready-made starting points, named after common car colours. */
enum class ThemePreset(val theme: CarTheme) {
    AMBER(CarTheme.DEFAULT),
    RED(CarTheme(0xFFFF3B30.toInt(), 0xFF0C0606.toInt(), 0xFF1C1012.toInt())),
    ORANGE(CarTheme(0xFFFF7A1A.toInt(), 0xFF0C0805.toInt(), 0xFF1C140E.toInt())),
    YELLOW(CarTheme(0xFFFFD60A.toInt(), 0xFF0B0A04.toInt(), 0xFF1A1809.toInt())),
    GREEN(CarTheme(0xFF30D97A.toInt(), 0xFF050B08.toInt(), 0xFF0F1B15.toInt())),
    BLUE(CarTheme(0xFF2F9BFF.toInt(), 0xFF050A12.toInt(), 0xFF0E1724.toInt())),
    SILVER(CarTheme(0xFFC9D2DC.toInt(), 0xFF0A0C0F.toInt(), 0xFF171B20.toInt())),
    WHITE(CarTheme(0xFF0A6CD6.toInt(), 0xFFEDF0F4.toInt(), 0xFFFFFFFF.toInt())),
}

/** Every colour the dashboard draws with. Text and gauges stay readable whatever was chosen. */
data class DashPalette(
    val background: Int,
    val panel: Int,
    val panelEdge: Int,
    val primary: Int,
    val secondary: Int,
    val track: Int,
    val accent: Int,
    val onAccent: Int,
    val good: Int,
    val alert: Int,
    val isLight: Boolean,
)

fun CarTheme.palette(): DashPalette {
    val background = opaque(background)
    val isLight = ThemeColors.luminance(background) > LIGHT_LUMINANCE
    // Text can't suit both a dark and a light surface, so panels stay on the background's side.
    val chosenPanel = opaque(panel)
    val sameSide = (ThemeColors.luminance(chosenPanel) > LIGHT_LUMINANCE) == isLight
    val text = if (isLight) DARK_TEXT else LIGHT_TEXT
    // Pure black or white always clears the limits below on this side, so moving toward it always ends.
    val limit = if (isLight) BLACK else WHITE
    // Away from the text colour, so a background close to the middle can't tip over to the other side.
    val panel = if (sameSide) chosenPanel else ThemeColors.mix(background, if (isLight) WHITE else BLACK, PANEL_FROM_BACKGROUND)

    fun onSurfaces(color: Int, minimum: Double) = readable(color, background, panel, limit, minimum)

    val primary = onSurfaces(text, TEXT_CONTRAST)
    val accent = onSurfaces(opaque(accent), GRAPHIC_CONTRAST)
    return DashPalette(
        background = background,
        panel = panel,
        panelEdge = ThemeColors.mix(panel, primary, EDGE_MIX),
        primary = primary,
        secondary = onSurfaces(ThemeColors.mix(primary, panel, HINT_FADE), TEXT_CONTRAST),
        track = ThemeColors.mix(panel, primary, TRACK_MIX),
        accent = accent,
        onAccent = ThemeColors.bestTextOn(accent),
        good = onSurfaces(if (isLight) DARK_GOOD else LIGHT_GOOD, GRAPHIC_CONTRAST),
        alert = onSurfaces(if (isLight) DARK_ALERT else LIGHT_ALERT, GRAPHIC_CONTRAST),
        isLight = isLight,
    )
}

object ThemeColors {
    /** WCAG relative luminance, from 0 for black to 1 for white. */
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** WCAG contrast ratio, from 1 for identical colours to 21 for black on white. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [from] moved toward [to] by [amount], from 0 to 1. */
    fun mix(from: Int, to: Int, amount: Double): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * amount).toInt().coerceIn(0, 255) shl shift
        }
        return OPAQUE or channel(16) or channel(8) or channel(0)
    }

    fun bestTextOn(color: Int): Int = if (contrast(BLACK, color) >= contrast(WHITE, color)) BLACK else WHITE
}

/** [color] moved just far enough toward [limit] to reach [minimum] contrast against both surfaces. */
private fun readable(color: Int, first: Int, second: Int, limit: Int, minimum: Double): Int {
    fun clears(c: Int) = ThemeColors.contrast(c, first) >= minimum && ThemeColors.contrast(c, second) >= minimum
    if (clears(color)) return color
    for (step in 1 until CONTRAST_STEPS) {
        val moved = ThemeColors.mix(color, limit, step.toDouble() / CONTRAST_STEPS)
        if (clears(moved)) return moved
    }
    return limit
}

private fun opaque(color: Int) = color or OPAQUE

private const val OPAQUE = 0xFF shl 24
private const val BLACK = OPAQUE
private const val WHITE = -1
private const val LIGHT_LUMINANCE = 0.179
private const val TEXT_CONTRAST = 4.5
private const val GRAPHIC_CONTRAST = 3.0
private const val CONTRAST_STEPS = 20
private const val PANEL_FROM_BACKGROUND = 0.06
private const val HINT_FADE = 0.38
private const val EDGE_MIX = 0.12
private const val TRACK_MIX = 0.16
private val LIGHT_TEXT = 0xFFF4F6F8.toInt()
private val DARK_TEXT = 0xFF11151B.toInt()
private val LIGHT_GOOD = 0xFF4CD787.toInt()
private val DARK_GOOD = 0xFF0F7A3D.toInt()
private val LIGHT_ALERT = 0xFFFF5A47.toInt()
private val DARK_ALERT = 0xFFC8321F.toInt()
