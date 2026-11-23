package dev.cooling.monitor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min

class OverlayDrawing {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val heavy = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun draw(canvas: Canvas, layout: OverlayLayout, state: MonitorState, width: Float,
             fontMultiplier: Float, selected: String? = null, showGrid: Boolean = false, drawBackground: Boolean = true,
             accent: Int? = null) {
        val ratio = width / layout.width
        val now = SystemClock.elapsedRealtime()
        canvas.save()
        canvas.scale(ratio, ratio)
        val opacity = layout.opacity
        if (drawBackground) fill(canvas, 0f, 0f, layout.width, layout.height, layout.radius, fade(layout.fill, opacity))
        if (showGrid) {
            for (x in 8 until layout.width.toInt() step 8)
                line(canvas, x.toFloat(), 0f, x.toFloat(), layout.height, 0x42747f93)
            for (y in 8 until layout.height.toInt() step 8)
                line(canvas, 0f, y.toFloat(), layout.width, y.toFloat(), 0x42747f93)
        }
        for (item in layout.widgets) {
            if (item.hidden && item.id != selected) continue
            canvas.save()
            canvas.translate(item.x, item.y)
            canvas.clipRect(0f, 0f, item.width, item.height)
            val alpha = if (item.hidden) opacity * 0.35f else opacity
            val ink = fade(item.ink, alpha)
            fill(canvas, 0f, 0f, item.width, item.height, item.radius, fade(item.fill, alpha))
            val sample = state.current(item.metric, now)
            val pad = item.padding.coerceAtMost(min(item.width, item.height) / 3f)
            when (item.type) {
                WidgetType.Value -> text(canvas, sample?.let { item.metric.text(it.value) } ?: "—", item, fontMultiplier, ink, pad, layout.fill == 0)
                WidgetType.Unit -> text(canvas, item.metric.unit, item, fontMultiplier, ink, pad, layout.fill == 0)
                WidgetType.Label -> text(canvas, item.metric.label, item, fontMultiplier, ink, pad, layout.fill == 0)
                WidgetType.Status -> text(canvas, state.phase.label, item, fontMultiplier, ink, pad, layout.fill == 0)
                WidgetType.Indicator -> {
                    val color = when (state.phase) {
                        Phase.Live -> accent ?: 0xff4f96fb.toInt()
                        Phase.Failed -> 0xffffaf93.toInt()
                        else -> 0xffdfc783.toInt()
                    }
                    paint.color = fade(color, alpha)
                    canvas.drawCircle(item.width / 2, item.height / 2, min(item.width, item.height) / 5, paint)
                }
                WidgetType.Divider -> line(canvas, pad, item.height / 2, item.width - pad, item.height / 2, ink)
                WidgetType.Trend -> trend(canvas, item, state.trend, now, ink, pad)
                WidgetType.Container -> Unit
            }
            if (item.border || item.id == selected) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = if (item.id == selected) 1.5f else 1f
                paint.color = if (item.id == selected) 0xffe1ac6b.toInt() else ink
                canvas.drawRoundRect(1f, 1f, item.width - 1, item.height - 1, item.radius, item.radius, paint)
                paint.style = Paint.Style.FILL
            }
            canvas.restore()
        }
        canvas.restore()
    }

    private fun text(canvas: Canvas, text: String, item: Widget, multiplier: Float, ink: Int, pad: Float, shadow: Boolean) {
        paint.color = ink
        paint.typeface = if (item.bold) heavy else regular
        paint.textSize = min(item.font * multiplier, item.height - 2 * pad)
        paint.fontFeatureSettings = "'tnum'"
        if (shadow) paint.setShadowLayer(2.8f, 0f, 1f, Color.BLACK)
        val baseline = (item.height - paint.descent() - paint.ascent()) / 2
        if (paint.measureText(text) <= item.width - 2 * pad) {
            canvas.drawText(text, pad, baseline, paint)
        } else if (item.width - 2 * pad > paint.measureText("…")) {
            val count = paint.breakText(text, true, item.width - 2 * pad - paint.measureText("…"), null)
            canvas.drawText(text.take(count) + "…", pad, baseline, paint)
        }
        paint.clearShadowLayer()
    }

    private fun trend(canvas: Canvas, item: Widget, all: List<Sample>, now: Long, ink: Int, pad: Float) {
        val frame = 120_000L
        val points = all.filter { now - it.elapsed in 0L..frame }
        val floor = (points.minOfOrNull { it.value } ?: 0) - 1
        val ceiling = (points.maxOfOrNull { it.value } ?: 0) + 1
        fun x(point: Sample) = item.width - pad - (now - point.elapsed).toFloat() / frame * (item.width - 2 * pad)
        fun y(point: Sample) = item.height - pad - ((point.value - floor).toFloat() / max(1, ceiling - floor)) * (item.height - 2 * pad)
        line(canvas, pad, item.height - pad, item.width - pad, item.height - pad, 0x70869abd)
        points.zipWithNext().forEach { (a, b) ->
            if (a.segment == b.segment && b.elapsed - a.elapsed < CoolerProtocol.staleMs)
                line(canvas, x(a), y(a), x(b), y(b), ink, 1.8f)
        }
        points.lastOrNull()?.let {
            paint.color = ink
            canvas.drawCircle(x(it), y(it), 2.1f, paint)
        }
    }

    private fun fill(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float, color: Int) {
        paint.color = color
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
    }

    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, stroke: Float = 1f) {
        paint.color = color
        paint.strokeWidth = stroke
        canvas.drawLine(x1, y1, x2, y2, paint)
    }

    private fun fade(color: Int, factor: Float) = (color and 0xffffff) or ((Color.alpha(color) * factor).toInt() shl 24)
}

fun compact(layout: OverlayLayout): OverlayLayout = LayoutPresets.stock[6].copy(
    fill = layout.fill, opacity = layout.opacity, scale = layout.scale, mode = layout.mode, followStyle = layout.followStyle
)
