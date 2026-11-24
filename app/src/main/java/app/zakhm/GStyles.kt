package app.zakhm

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zakhm.core.SteadyPush
import app.zakhm.core.TelemetrySnapshot
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.hypot

/** How the G-force instrument is drawn. All show the same readings. */
enum class GStyle(@StringRes val label: Int) {
    /** The circle with the dot and its trail, and rings on round values. */
    CIRCLE(R.string.g_style_circle),

    /** A ring that fills with the push, between a braking bar and an accelerating bar. */
    RING(R.string.g_style_ring),

    /** Nested frames around the number, lit on the side the push goes, more of them the harder it is. */
    WINDOW(R.string.g_style_window),
}

private const val PEDAL_SEGMENTS = 10
private const val RING_START_DEGREES = 135f
private const val RING_SWEEP_DEGREES = 270f

/** The number's size against the ring's width. */
private const val RING_NUMBER_SHARE = 0.2f

/** From here the fill turns to the alert colour, as the circle's dot does. */
private const val HIGH_G = 0.6

private fun gText(value: Double?) = value?.takeIf { it.isFinite() }?.let { "%.2f".format(Locale.US, it) } ?: "--"

/** The meter's steady view of the push, kept across frames; see [SteadyPush]. */
@Composable
private fun rememberSteadyPush(s: TelemetrySnapshot, range: Double, bands: Int): SteadyPush {
    val steady = remember { SteadyPush() }
    steady.update(System.nanoTime() / 1e9, s.longitudinalG, s.lateralG, range, bands)
    return steady
}

private fun share(value: Double, range: Double) = (value / range).coerceIn(0.0, 1.0).toFloat()

/** A short message in place of the number, which keeps its own language's direction. */
@Composable
private fun Waiting(text: String, size: Int) {
    Text(
        text,
        color = Accent,
        fontSize = size.sp,
        maxLines = 3,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        style = TextStyle(textDirection = TextDirection.Content),
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/**
 * A ring that fills with the strength of the push, a marker on it pointing the way of the
 * push, and between two segment bars: braking on one side, speeding up on the other.
 */
@Composable
internal fun GRing(s: TelemetrySnapshot, range: Double, waiting: String?, modifier: Modifier, large: Boolean) {
    val colors = LocalDash.current
    // Drawn steady; the number shows the reading as it is.
    val steady = rememberSteadyPush(s, range, 1)
    val long = steady.long
    val lat = steady.lat
    val total = steady.total
    val fill = if (total >= HIGH_G) colors.alert else colors.accent
    // Braking and speeding up are the car's own sides, so the bars never mirror for Arabic.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.panel().padding(12.dp), contentAlignment = Alignment.Center) {
            val barWidth = if (large) 56.dp else 44.dp
            val gap = 12.dp
            // The ring as big as the panel allows, and the bars as tall as the ring, so they read as one.
            val ring = minOf(maxHeight, maxWidth - barWidth * 2 - gap * 2)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            PedalBar(share(maxOf(0.0, -long), range), colors.alert, R.string.peak_brake, Modifier.width(barWidth).height(ring))
            Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val width = size.minDimension * 0.075f
                        val radius = size.minDimension / 2 - width
                        val topLeft = Offset(center.x - radius, center.y - radius)
                        val arcSize = Size(radius * 2, radius * 2)
                        // A faint disc in the fill colour, like a lit face behind glass.
                        drawCircle(fill.copy(alpha = 0.08f), radius - width, center)
                        drawArc(colors.track, RING_START_DEGREES, RING_SWEEP_DEGREES, false, topLeft, arcSize, style = Stroke(width, cap = StrokeCap.Round))
                        val swept = RING_SWEEP_DEGREES * share(total, range)
                        if (swept > 0f) {
                            drawArc(fill, RING_START_DEGREES, swept, false, topLeft, arcSize, style = Stroke(width, cap = StrokeCap.Round))
                        }
                        // Where the push points: up for speeding up, left for a left turn, as on the circle.
                        if (steady.shown) {
                            val x = -lat
                            val y = -long
                            val length = hypot(x, y).toFloat()
                            // On the ring itself, clear of the number in the middle.
                            val at = center + Offset((x / length).toFloat(), (y / length).toFloat()) * radius
                            drawCircle(colors.background, width * 0.75f, at)
                            drawCircle(colors.primary, width * 0.55f, at)
                        }
                    }
                    if (waiting != null) {
                        Waiting(waiting, if (large) 16 else 13)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Sized from the ring, so it always fits inside it.
                            Text(gText(s.totalG), color = colors.primary, fontSize = (ring.value * RING_NUMBER_SHARE).sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.unit_g), color = colors.secondary, fontSize = if (large) 16.sp else 12.sp, letterSpacing = 2.sp)
                        }
                    }
                }
            }
            PedalBar(share(maxOf(0.0, long), range), colors.good, R.string.peak_accel, Modifier.width(barWidth).height(ring))
        }
        }
    }
}

/** A column of segments lit from the bottom, with its name under it. */
@Composable
private fun PedalBar(share: Float, color: Color, @StringRes label: Int, modifier: Modifier) {
    val colors = LocalDash.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val gap = 3.dp.toPx()
            val segment = (size.height - gap * (PEDAL_SEGMENTS - 1)) / PEDAL_SEGMENTS
            val lit = ceil(share * PEDAL_SEGMENTS).toInt()
            for (i in 0 until PEDAL_SEGMENTS) {
                val top = size.height - (i + 1) * segment - i * gap
                drawRoundRect(
                    if (i < lit) color else colors.track,
                    Offset(0f, top),
                    Size(size.width, segment),
                    CornerRadius(2.dp.toPx()),
                )
            }
        }
        Text(stringResource(label), color = colors.secondary, fontSize = DashType.Label, maxLines = 1)
    }
}

/**
 * Nested frames around the number, lit on the side the push goes. The harder the push, the
 * more frames light, outward from the number, and the wider the lit part: the theme colour
 * first, then white, then the alert colour on the outermost.
 */
@Composable
internal fun GWindow(s: TelemetrySnapshot, view: GView, range: Double, waiting: String?, modifier: Modifier, large: Boolean) {
    val colors = LocalDash.current
    // Drawn steady: lights at a clear push, holds its place a moment, and fades only once the push has.
    val steady = rememberSteadyPush(s, range, LIT_FRAMES)
    // The same way round as the circle: the car's movement, or the push felt in the seat.
    val sign = if (view == GView.DRIVER) -1.0 else 1.0
    // The display's own colours: cyan, then white, then red. White stays dark on a light theme, to show.
    val ringColors = listOf(WINDOW_CYAN, if (colors.isLight) colors.primary else WINDOW_WHITE, WINDOW_RED)
    // As on the display, the number and its box flash red for a moment when the push first
    // reaches its hardest, then go back to white while the red band stays lit.
    val hot = steady.lit == LIT_FRAMES
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(hot) {
        flash = hot
        if (hot) {
            delay(HOT_FLASH_MS)
            flash = false
        }
    }
    // Left and right are the car's real directions, so they never mirror for Arabic.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.panel().padding(12.dp), contentAlignment = Alignment.Center) {
            // As big as the panel allows: long and low in a wide panel, taller in a tall one, so a
            // portrait panel isn't left mostly empty above and below.
            val aspect = (maxWidth / maxHeight).coerceIn(FRAMES_MIN_ASPECT, FRAMES_MAX_ASPECT)
            val width = minOf(maxWidth, maxHeight * aspect, if (large) 640.dp else 440.dp)
            val height = width / aspect
            Box(Modifier.width(width).height(height), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val gap = size.height * FRAME_GAP_SHARE
                    val line = 2.dp.toPx()
                    fun frame(i: Float): Path {
                        // Frame 0 is the outermost; the number sits inside the last one. Halfway
                        // numbers give the line midway between two frames.
                        val inset = gap * i + line
                        val w = size.width - 2 * inset
                        val h = size.height - 2 * inset
                        return Path().apply {
                            addRoundRect(
                                androidx.compose.ui.geometry.RoundRect(
                                    inset, inset, inset + w, inset + h,
                                    CornerRadius(h * FRAME_CORNER_SHARE, h * FRAME_CORNER_SHARE),
                                ),
                            )
                        }
                    }
                    for (i in 0 until FRAME_COUNT) drawPath(frame(i.toFloat()), colors.track, style = Stroke(line))

                    val strength = share(steady.total, range)
                    // How many bands light: the inner one at once, the others as the push grows.
                    val litBands = steady.lit
                    if (litBands > 0) {
                        // As on a race car's onboard display, the bands light in one of eight places
                        // around the number, the way of the push: a side, the top or bottom, or a
                        // corner between. On screen, as on the circle: x right, y down.
                        val angle = kotlin.math.atan2(-sign * steady.placeLong(), -sign * steady.placeLat())
                        val quarter = kotlin.math.PI / 2
                        val axis = kotlin.math.round(angle / quarter) * quarter
                        // More than half way between two sides: a corner.
                        val corner = if (kotlin.math.abs(angle - axis) > CORNER_DEGREES * kotlin.math.PI / 180) {
                            Offset(kotlin.math.sign(kotlin.math.cos(angle)).toFloat(), kotlin.math.sign(kotlin.math.sin(angle)).toFloat())
                        } else {
                            null
                        }
                        val dx = kotlin.math.round(kotlin.math.cos(axis)).toFloat()
                        val dy = kotlin.math.round(kotlin.math.sin(axis)).toFloat()
                        // Toward a side, a block cut straight across, wider as the push grows:
                        // the same cut through every band, so together they read as one block of stripes.
                        val sideways = dx != 0f
                        val minHalf = if (sideways) SIDE_MIN_HALF else TOP_MIN_HALF
                        val maxHalf = if (sideways) SIDE_MAX_HALF else TOP_MAX_HALF
                        val half = minHalf + (maxHalf - minHalf) * strength
                        val side = if (sideways) {
                            val x = center.x + dx * size.width
                            androidx.compose.ui.geometry.Rect(minOf(center.x, x), center.y - half * size.height / 2, maxOf(center.x, x), center.y + half * size.height / 2)
                        } else {
                            val y = center.y + dy * size.height
                            androidx.compose.ui.geometry.Rect(center.x - half * size.width / 2, minOf(center.y, y), center.x + half * size.width / 2, maxOf(center.y, y))
                        }
                        for (ring in 0 until litBands) {
                            val band = FRAME_COUNT - 1.5f - ring
                            // Toward a corner, just each band's rounded corner piece.
                            val area = corner?.let { (sx, sy) ->
                                val inset = gap * band + line
                                val halfWidth = size.width / 2 - inset
                                val halfHeight = size.height / 2 - inset
                                val radius = 2 * halfHeight * FRAME_CORNER_SHARE
                                val x1 = center.x + sx * (halfWidth - radius)
                                val x2 = center.x + sx * (halfWidth + gap)
                                val y1 = center.y + sy * (halfHeight - radius)
                                val y2 = center.y + sy * (halfHeight + gap)
                                androidx.compose.ui.geometry.Rect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
                            } ?: side
                            clipRect(area.left, area.top, area.right, area.bottom) {
                                // A solid band filling the space between two frames: the frame lines
                                // left showing between the bands divide them.
                                drawPath(frame(band), ringColors[ring], style = Stroke(gap - line * BAND_DIVIDER_LINES))
                            }
                        }
                    }

                    // The number's own box, the innermost frame filled in.
                    drawPath(frame(FRAME_COUNT - 1f), colors.background)
                    if (flash) drawPath(frame(FRAME_COUNT - 1f), WINDOW_RED.copy(alpha = HOT_BOX_ALPHA))
                    drawPath(frame(FRAME_COUNT - 1f), if (flash) WINDOW_RED else colors.secondary.copy(alpha = 0.6f), style = Stroke(line))
                }
                if (waiting != null) {
                    // Inside the number's box, wrapping onto two lines rather than running past its frames.
                    val boxWidth = width - height * (2 * FRAME_GAP_SHARE * (FRAME_COUNT - 1)) - 16.dp
                    Box(Modifier.width(boxWidth), contentAlignment = Alignment.Center) {
                        Waiting(waiting, if (large) 15 else 12)
                    }
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            gText(s.totalG),
                            color = if (flash) WINDOW_RED else colors.primary,
                            fontSize = (height.value * WINDOW_NUMBER_SHARE).sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.unit_g),
                            color = colors.secondary,
                            fontSize = (height.value * WINDOW_NUMBER_SHARE * 0.5f).sp,
                            modifier = Modifier.padding(start = 3.dp, bottom = (height.value * 0.03f).dp),
                        )
                    }
                }
            }
        }
    }
}

/** Four frames: three that light, and the number's box inside them. */
private const val FRAME_COUNT = 4
private const val LIT_FRAMES = 3
private const val FRAMES_MIN_ASPECT = 1.6f
private const val FRAMES_MAX_ASPECT = 2.1f
private const val FRAME_GAP_SHARE = 0.11f
private const val FRAME_CORNER_SHARE = 0.32f

/**
 * Half a lit block's width across the push, against half the frames' height (for a side) or
 * width (for the top or bottom): for a light push and one at the top of the range. Hard
 * braking lights most of the top, as on the display.
 */
private const val SIDE_MIN_HALF = 0.22f
private const val SIDE_MAX_HALF = 0.36f
private const val TOP_MIN_HALF = 0.25f
private const val TOP_MAX_HALF = 0.55f

/** The display's colours: the bands light cyan, then white, then red. */
private val WINDOW_CYAN = Color(0xFF35CCF2)
private val WINDOW_WHITE = Color(0xFFF4F6F8)
private val WINDOW_RED = Color(0xFFFF3B30)

/** How far, in degrees, a push may lean off straight to a side before only the corner lights. */
private const val CORNER_DEGREES = 22.5

/** How long the number and its box flash red when the push first reaches its hardest. */
private const val HOT_FLASH_MS = 1_200L

/** How strongly the number's box glows red while it flashes. */
private const val HOT_BOX_ALPHA = 0.22f

private const val WINDOW_NUMBER_SHARE = 0.2f

/** The dark gap kept between two lit bands, in frame-line widths, so each band stands apart. */
private const val BAND_DIVIDER_LINES = 3f

