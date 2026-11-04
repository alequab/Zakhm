package app.zakhm

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.zakhm.core.CarTheme
import app.zakhm.core.palette

/** The active car's colours, ready to draw with. */
@Immutable
data class DashColors(
    val background: Color,
    val panel: Color,
    val panelEdge: Color,
    val primary: Color,
    val secondary: Color,
    val track: Color,
    val accent: Color,
    val onAccent: Color,
    val good: Color,
    val alert: Color,
    val isLight: Boolean,
)

fun CarTheme.dashColors(): DashColors {
    val p = palette()
    return DashColors(
        background = Color(p.background),
        panel = Color(p.panel),
        panelEdge = Color(p.panelEdge),
        primary = Color(p.primary),
        secondary = Color(p.secondary),
        track = Color(p.track),
        accent = Color(p.accent),
        onAccent = Color(p.onAccent),
        good = Color(p.good),
        alert = Color(p.alert),
        isLight = p.isLight,
    )
}

val LocalDash = staticCompositionLocalOf { CarTheme.DEFAULT.dashColors() }

internal val Background: Color @Composable @ReadOnlyComposable get() = LocalDash.current.background
internal val Panel: Color @Composable @ReadOnlyComposable get() = LocalDash.current.panel
internal val PanelEdge: Color @Composable @ReadOnlyComposable get() = LocalDash.current.panelEdge
internal val Primary: Color @Composable @ReadOnlyComposable get() = LocalDash.current.primary
internal val Secondary: Color @Composable @ReadOnlyComposable get() = LocalDash.current.secondary
internal val Accent: Color @Composable @ReadOnlyComposable get() = LocalDash.current.accent
internal val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalDash.current.onAccent
internal val Good: Color @Composable @ReadOnlyComposable get() = LocalDash.current.good
internal val Alert: Color @Composable @ReadOnlyComposable get() = LocalDash.current.alert

/** Applies [theme] to everything inside, including dialogs and the settings sheet. */
@Composable
fun DashTheme(theme: CarTheme, content: @Composable () -> Unit) {
    val colors = remember(theme) { theme.dashColors() }
    val scheme = remember(colors) {
        val c = colors
        if (c.isLight) {
            lightColorScheme(
                primary = c.accent,
                onPrimary = c.onAccent,
                background = c.background,
                onBackground = c.primary,
                surface = c.panel,
                onSurface = c.primary,
                onSurfaceVariant = c.secondary,
                surfaceVariant = c.track,
                surfaceContainerLowest = c.panel,
                surfaceContainerLow = c.panel,
                surfaceContainer = c.panel,
                surfaceContainerHigh = c.panel,
                surfaceContainerHighest = c.panel,
                outline = c.panelEdge,
                error = c.alert,
            )
        } else {
            darkColorScheme(
                primary = c.accent,
                onPrimary = c.onAccent,
                background = c.background,
                onBackground = c.primary,
                surface = c.panel,
                onSurface = c.primary,
                onSurfaceVariant = c.secondary,
                surfaceVariant = c.track,
                surfaceContainerLowest = c.panel,
                surfaceContainerLow = c.panel,
                surfaceContainer = c.panel,
                surfaceContainerHigh = c.panel,
                surfaceContainerHighest = c.panel,
                outline = c.panelEdge,
                error = c.alert,
            )
        }
    }
    CompositionLocalProvider(LocalDash provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
