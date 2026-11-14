package app.zakhm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** What each settings icon shows. They share one stroke, drawn on a 24-unit grid like Material's icons. */
internal enum class SettingsIcon { GUIDE, CAR, SOURCE, DISPLAY, G_FORCE, DRIVES, TOOLS, BACK, FORWARD }

/**
 * A settings icon, drawn here because the app carries no icon library. Arrows point the way
 * the page moves, so they turn round in Arabic.
 */
@Composable
internal fun SettingsIconImage(icon: SettingsIcon, color: Color, modifier: Modifier = Modifier) {
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl && (icon == SettingsIcon.BACK || icon == SettingsIcon.FORWARD)
    Canvas(modifier.size(24.dp)) {
        val unit = size.width / 24f
        val pen = Stroke(width = 2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), pen.width, StrokeCap.Round)
        fun path(build: Path.() -> Unit) = drawPath(Path().apply(build), color, style = pen)
        fun Path.to(x: Float, y: Float) = lineTo(x * unit, y * unit)
        fun Path.from(x: Float, y: Float) = moveTo(x * unit, y * unit)

        val draw: DrawScope.() -> Unit = {
            when (icon) {
                SettingsIcon.GUIDE -> {
                    drawCircle(color, 9f * unit, p(12f, 12f), style = pen)
                    line(12f, 11f, 12f, 16.5f)
                    drawCircle(color, 1.3f * unit, p(12f, 7.6f))
                }
                SettingsIcon.CAR -> {
                    path {
                        from(3f, 16f); to(3f, 12.5f); to(5.5f, 11.5f); to(8f, 7.5f); to(15.5f, 7.5f)
                        to(18.5f, 11.5f); to(21f, 12.5f); to(21f, 16f); to(19.5f, 16f)
                        from(8.5f, 16f); to(15.5f, 16f)
                        from(4.5f, 16f); to(3f, 16f)
                        from(5.5f, 11.5f); to(18.5f, 11.5f)
                    }
                    drawCircle(color, 2f * unit, p(6.5f, 16.5f), style = pen)
                    drawCircle(color, 2f * unit, p(17.5f, 16.5f), style = pen)
                }
                SettingsIcon.SOURCE -> {
                    // A gauge: where the speed reading comes from.
                    drawArc(color, 150f, 240f, false, p(3f, 4f), Size(18f * unit, 18f * unit), style = pen)
                    line(12f, 13f, 16.5f, 8.5f)
                    drawCircle(color, 1.6f * unit, p(12f, 13f))
                }
                SettingsIcon.DISPLAY -> {
                    drawRoundRect(color, p(7f, 2.5f), Size(10f * unit, 19f * unit), CornerRadius(2.5f * unit), style = pen)
                    line(10.5f, 18.5f, 13.5f, 18.5f)
                }
                SettingsIcon.G_FORCE -> {
                    drawCircle(color, 9f * unit, p(12f, 12f), style = pen)
                    drawCircle(color, 4.5f * unit, p(12f, 12f), style = Stroke(width = 1.4f * unit))
                    drawCircle(color, 2.2f * unit, p(15.5f, 9f))
                }
                SettingsIcon.DRIVES -> {
                    path {
                        from(5f, 19f)
                        cubicTo(5f * unit, 13f * unit, 12f * unit, 15f * unit, 12f * unit, 11f * unit)
                        cubicTo(12f * unit, 7f * unit, 19f * unit, 9f * unit, 19f * unit, 5f * unit)
                    }
                    drawCircle(color, 2f * unit, p(5f, 19f))
                    drawCircle(color, 2.2f * unit, p(19f, 5f), style = pen)
                }
                SettingsIcon.TOOLS -> {
                    // Start again: calibrate, reset peaks.
                    drawArc(color, -60f, 290f, false, p(4f, 4f), Size(16f * unit, 16f * unit), style = pen)
                    path { from(16f, 3f); to(16.5f, 6.7f); to(12.8f, 7.3f) }
                }
                SettingsIcon.BACK -> path { from(19f, 12f); to(5f, 12f); from(11f, 6f); to(5f, 12f); to(11f, 18f) }
                SettingsIcon.FORWARD -> path { from(9.5f, 6f); to(15.5f, 12f); to(9.5f, 18f) }
            }
        }
        if (mirrored) scale(-1f, 1f) { draw() } else draw()
    }
}
