package app.zakhm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import app.zakhm.core.TripExport.Picture
import app.zakhm.core.TripLegend
import java.io.ByteArrayOutputStream

/**
 * The pictures packed into a drive's KMZ, drawn here so the map needs no internet for
 * them and they match the app. Each is drawn on a 64-pixel square unless said otherwise.
 */
internal object TripPictures {
    private const val SIZE = 64
    private const val EDGE = 0xFF1B1B1B.toInt()
    private const val GREEN = 0xFF2ECC71.toInt()
    private const val RED = 0xFFE81E1E.toInt()
    private const val BLUE = 0xFF2B6CFF.toInt()
    private const val NAVY = 0xFF263238.toInt()
    private const val PURPLE = 0xFF8E44AD.toInt()
    private const val AMBER = 0xFFFFB300.toInt()

    fun all(legend: TripLegend, rightToLeft: Boolean): Map<Picture, ByteArray> = mapOf(
        Picture.CIRCLE to icon { circle(it, Color.WHITE) },
        Picture.START to icon { pin(it, GREEN) { c, p -> c.drawPath(triangle(26f, 14f, 42f, 24f, 26f, 34f), p) } },
        Picture.END to icon { pin(it, RED) { c, p -> c.drawRect(24f, 16f, 40f, 32f, p) } },
        Picture.TOP_SPEED to icon(::flag),
        Picture.CAR to icon(::car),
        Picture.ARROW to icon(::arrow),
        Picture.POST to icon(::post),
        Picture.STOP to icon(::pause),
        Picture.LAUNCH to icon(::bolt),
        Picture.GAP to icon(::warning),
        Picture.LEGEND to png(legend(legend, rightToLeft)),
    )

    private fun icon(draw: (Canvas) -> Unit): ByteArray {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        return png(bitmap)
    }

    private fun png(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun fill(colour: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour; style = Paint.Style.FILL }

    private fun stroke(colour: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Path().apply {
        moveTo(x1, y1)
        lineTo(x2, y2)
        lineTo(x3, y3)
        close()
    }

    /** A plain disc with a dark edge. White, so the map can tint it. */
    private fun circle(c: Canvas, colour: Int) {
        c.drawCircle(32f, 32f, 26f, fill(colour))
        c.drawCircle(32f, 32f, 26f, stroke(EDGE, 4f))
    }

    /** A map pin standing on its tip at the bottom middle, with a sign drawn in its head. */
    private fun pin(c: Canvas, colour: Int, sign: (Canvas, Paint) -> Unit) {
        val shape = Path().apply {
            moveTo(32f, 62f)
            cubicTo(22f, 46f, 10f, 38f, 10f, 24f)
            arcTo(RectF(10f, 2f, 54f, 46f), 180f, 180f)
            cubicTo(54f, 38f, 42f, 46f, 32f, 62f)
            close()
        }
        c.drawPath(shape, fill(colour))
        c.drawPath(shape, stroke(Color.WHITE, 3f))
        sign(c, fill(Color.WHITE))
    }

    /** A chequered flag on a pole, for the top speed. */
    private fun flag(c: Canvas) {
        c.drawRect(10f, 6f, 15f, 60f, fill(EDGE))
        val cell = 8f
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                val colour = if ((row + col) % 2 == 0) EDGE else Color.WHITE
                c.drawRect(15f + col * cell, 8f + row * cell, 15f + (col + 1) * cell, 8f + (row + 1) * cell, fill(colour))
            }
        }
        c.drawRect(15f, 8f, 55f, 40f, stroke(EDGE, 2f))
    }

    /** A car seen from above, nose up. */
    private fun car(c: Canvas) {
        val body = RectF(18f, 4f, 46f, 60f)
        c.drawRoundRect(body, 10f, 10f, fill(BLUE))
        c.drawRoundRect(body, 10f, 10f, stroke(Color.WHITE, 3f))
        // Windscreen and rear window.
        c.drawRoundRect(RectF(22f, 18f, 42f, 28f), 3f, 3f, fill(NAVY))
        c.drawRoundRect(RectF(22f, 44f, 42f, 51f), 3f, 3f, fill(NAVY))
    }

    /** An arrow pointing north, white with a dark edge so it shows on any colour of path. */
    private fun arrow(c: Canvas) {
        val shape = Path().apply {
            moveTo(32f, 6f)
            lineTo(54f, 34f)
            lineTo(40f, 34f)
            lineTo(40f, 58f)
            lineTo(24f, 58f)
            lineTo(24f, 34f)
            lineTo(10f, 34f)
            close()
        }
        c.drawPath(shape, fill(Color.WHITE))
        c.drawPath(shape, stroke(EDGE, 4f))
    }

    /** A small white square, for distance posts. */
    private fun post(c: Canvas) {
        val box = RectF(16f, 16f, 48f, 48f)
        c.drawRoundRect(box, 6f, 6f, fill(Color.WHITE))
        c.drawRoundRect(box, 6f, 6f, stroke(EDGE, 4f))
    }

    /** A pause sign, for where the car stood still. */
    private fun pause(c: Canvas) {
        circle(c, NAVY)
        c.drawCircle(32f, 32f, 26f, stroke(Color.WHITE, 3f))
        c.drawRoundRect(RectF(22f, 19f, 29f, 45f), 2f, 2f, fill(Color.WHITE))
        c.drawRoundRect(RectF(35f, 19f, 42f, 45f), 2f, 2f, fill(Color.WHITE))
    }

    /** A lightning bolt, for a launch from standstill. */
    private fun bolt(c: Canvas) {
        circle(c, PURPLE)
        c.drawCircle(32f, 32f, 26f, stroke(Color.WHITE, 3f))
        val shape = Path().apply {
            moveTo(36f, 12f)
            lineTo(20f, 36f)
            lineTo(31f, 36f)
            lineTo(27f, 53f)
            lineTo(44f, 28f)
            lineTo(33f, 28f)
            close()
        }
        c.drawPath(shape, fill(Color.WHITE))
    }

    /** A warning triangle, for where GPS was lost. */
    private fun warning(c: Canvas) {
        val shape = triangle(32f, 6f, 60f, 56f, 4f, 56f)
        c.drawPath(shape, fill(AMBER))
        c.drawPath(shape, stroke(EDGE, 4f))
        c.drawRoundRect(RectF(29f, 22f, 35f, 42f), 2f, 2f, fill(EDGE))
        c.drawCircle(32f, 49f, 3.5f, fill(EDGE))
    }

    /** The speed colours as a small dark card: a title, then one swatch and range a row. */
    private fun legend(legend: TripLegend, rightToLeft: Boolean): Bitmap {
        val pad = 14f
        val swatchW = 36f
        val swatchH = 20f
        val rowH = 34f
        val gap = 12f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        }
        val rowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
        }
        val textW = (legend.rows.maxOfOrNull { rowPaint.measureText(it.second) } ?: 0f)
            .coerceAtLeast(titlePaint.measureText(legend.title) - swatchW - gap)
        val width = (pad * 2 + swatchW + gap + textW + 4).toInt()
        val titleH = 40f
        val height = (pad * 2 + titleH + rowH * legend.rows.size).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 14f, 14f, fill(0xCC101418.toInt()))

        fun text(value: String, paint: TextPaint, x: Float, y: Float, w: Float) {
            // A layout, not drawText, so Arabic joins up and mixed digits and words keep their order.
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, w.toInt().coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(if (rightToLeft) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
                .setMaxLines(1)
                .build()
            c.save()
            c.translate(x, y)
            layout.draw(c)
            c.restore()
        }

        val inner = width - pad * 2
        text(legend.title, titlePaint, pad, pad, inner)
        legend.rows.forEachIndexed { i, (rgb, label) ->
            val top = pad + titleH + i * rowH
            val swatchX = if (rightToLeft) width - pad - swatchW else pad
            val swatch = RectF(swatchX, top + (rowH - swatchH) / 2, swatchX + swatchW, top + (rowH + swatchH) / 2)
            c.drawRoundRect(swatch, 4f, 4f, fill((0xFF000000 or rgb.toLong(16)).toInt()))
            val textX = if (rightToLeft) pad else pad + swatchW + gap
            text(label, rowPaint, textX, top + 3f, inner - swatchW - gap)
        }
        return bitmap
    }
}
