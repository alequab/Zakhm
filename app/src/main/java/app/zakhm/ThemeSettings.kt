package app.zakhm

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zakhm.core.CarTheme
import app.zakhm.core.ThemePreset

private enum class ThemePart(@StringRes val label: Int) {
    ACCENT(R.string.theme_part_accent),
    BACKGROUND(R.string.theme_part_background),
    PANEL(R.string.theme_part_panel),
}

@get:StringRes
private val ThemePreset.label: Int
    get() = when (this) {
        ThemePreset.AMBER -> R.string.theme_amber
        ThemePreset.RED -> R.string.theme_red
        ThemePreset.ORANGE -> R.string.theme_orange
        ThemePreset.YELLOW -> R.string.theme_yellow
        ThemePreset.GREEN -> R.string.theme_green
        ThemePreset.BLUE -> R.string.theme_blue
        ThemePreset.SILVER -> R.string.theme_silver
        ThemePreset.WHITE -> R.string.theme_white
    }

private const val PRESETS_PER_ROW = 4
private const val PREVIEW_SWEEP = 270f
private const val PREVIEW_START = 135f
private const val PREVIEW_FILL = 0.55f

/** Picks and edits the active car's colours. [onChange] says whether the change is final and worth saving. */
@Composable
internal fun ThemeSettings(theme: CarTheme, onChange: (CarTheme, Boolean) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var part by rememberSaveable { mutableStateOf(ThemePart.ACCENT) }

    SectionTitle(R.string.section_theme)
    Hint(stringResource(R.string.theme_hint))
    ThemePreview()
    ThemePreset.entries.chunked(PRESETS_PER_ROW).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { preset ->
                PresetSwatch(preset, preset.theme == theme, Modifier.weight(1f)) { onChange(preset.theme, true) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactButton(
            stringResource(if (editing) R.string.theme_done else R.string.theme_customize),
            { editing = !editing },
            Modifier.weight(1f),
        )
        CompactButton(
            stringResource(R.string.theme_reset),
            { onChange(CarTheme.DEFAULT, true) },
            Modifier.weight(1f),
        )
    }
    if (!editing) return

    ChoiceRow(ThemePart.entries.map { it to stringResource(it.label) }, part, { part = it })
    val current = when (part) {
        ThemePart.ACCENT -> theme.accent
        ThemePart.BACKGROUND -> theme.background
        ThemePart.PANEL -> theme.panel
    }
    ColorSliders(part, current) { argb, final ->
        val changed = when (part) {
            ThemePart.ACCENT -> theme.copy(accent = argb)
            ThemePart.BACKGROUND -> theme.copy(background = argb)
            ThemePart.PANEL -> theme.copy(panel = argb)
        }
        onChange(changed, final)
    }
    Hint(stringResource(R.string.theme_readable_hint))
}

/** A small gauge in the theme being edited, because the sheet can hide the real one. */
@Composable
private fun ThemePreview() {
    val colors = LocalDash.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(colors.background, RoundedCornerShape(14.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(76.dp).panel(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(62.dp)) {
                val stroke = 5.dp.toPx()
                val topLeft = Offset(stroke / 2, stroke / 2)
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(colors.track, PREVIEW_START, PREVIEW_SWEEP, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(colors.accent, PREVIEW_START, PREVIEW_SWEEP * PREVIEW_FILL, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text("88", color = colors.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PresetSwatch(preset: ThemePreset, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val accent = Color(preset.theme.accent)
    val back = Color(preset.theme.background)
    Column(
        modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Accent else PanelEdge, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(30.dp).background(back, CircleShape).border(1.dp, PanelEdge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(16.dp).background(accent, CircleShape))
        }
        Text(
            stringResource(preset.label),
            color = Primary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private fun toHsv(argb: Int): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }

private fun fromHsv(hue: Float, saturation: Float, value: Float): Int =
    android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))

/** Holds its own hue, because a grey colour has none and would otherwise lose it while sliding. */
@Composable
private fun ColorSliders(part: ThemePart, argb: Int, onChange: (Int, Boolean) -> Unit) {
    var hsv by remember(part) { mutableStateOf(toHsv(argb)) }
    LaunchedEffect(argb) {
        if (fromHsv(hsv[0], hsv[1], hsv[2]) != argb or OPAQUE_ALPHA) hsv = toHsv(argb)
    }
    val (hue, saturation, value) = hsv
    fun change(h: Float, s: Float, v: Float) {
        hsv = floatArrayOf(h, s, v)
        onChange(fromHsv(h, s, v), false)
    }
    val finish = { onChange(fromHsv(hsv[0], hsv[1], hsv[2]), true) }

    GradientSlider(
        R.string.theme_hue,
        hue,
        0f..360f,
        Brush.horizontalGradient((0..6).map { Color(fromHsv(it * 60f, 1f, 1f)) }),
        Color(fromHsv(hue, 1f, 1f)),
        { change(it, saturation, value) },
        finish,
    )
    GradientSlider(
        R.string.theme_saturation,
        saturation,
        0f..1f,
        Brush.horizontalGradient(listOf(Color(fromHsv(hue, 0f, value)), Color(fromHsv(hue, 1f, value)))),
        Color(fromHsv(hue, saturation, value)),
        { change(hue, it, value) },
        finish,
    )
    GradientSlider(
        R.string.theme_brightness,
        value,
        0f..1f,
        Brush.horizontalGradient(listOf(Color.Black, Color(fromHsv(hue, saturation, 1f)))),
        Color(fromHsv(hue, saturation, value)),
        { change(hue, saturation, it) },
        finish,
    )
}

private const val OPAQUE_ALPHA = 0xFF shl 24

@Composable
private fun GradientSlider(
    @StringRes label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    track: Brush,
    thumb: Color,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Column {
        Hint(stringResource(label))
        // The colours run left to right in every language, so the slider has to as well.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .background(track, CircleShape)
                        .border(1.dp, PanelEdge, CircleShape),
                )
                Slider(
                    value = value,
                    onValueChange = onChange,
                    onValueChangeFinished = onFinished,
                    valueRange = range,
                    colors = SliderDefaults.colors(
                        thumbColor = if (thumb.toArgb() == Color.Black.toArgb()) LocalDash.current.primary else thumb,
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
