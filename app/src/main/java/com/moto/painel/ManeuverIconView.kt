package com.moto.painel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Ícones de manobra realmente vetoriais, desenhados no Android Canvas:
 * sem emoji, sem fonte externa e sem imagens remotas.
 * O tamanho é controlado pelo layout (32dp no painel, 52dp no mapa).
 */
internal class ManeuverIconView(context: Context, private val tint: Int) : View(context) {
    private var maneuverType = "straight"
    private var modifier = "straight"
    private var exitNumber = 0
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.FILL
    }
    private val path = Path()

    fun setManeuver(type: String, direction: String, exit: Int = 0) {
        if (maneuverType == type && modifier == direction && exitNumber == exit) return
        maneuverType = type
        modifier = direction
        exitNumber = exit
        invalidate()
    }

    private fun line(vararg coords: Float) {
        path.reset()
        if (coords.size < 4) return
        path.moveTo(coords[0], coords[1])
        var i = 2
        while (i < coords.size - 1) {
            path.lineTo(coords[i], coords[i + 1])
            i += 2
        }
    }

    private fun head(canvas: Canvas, x: Float, y: Float, radians: Double) {
        val wing = 9.0
        val angle = 0.62
        canvas.drawLine(
            x, y,
            (x - wing * cos(radians - angle)).toFloat(),
            (y - wing * sin(radians - angle)).toFloat(),
            stroke
        )
        canvas.drawLine(
            x, y,
            (x - wing * cos(radians + angle)).toFloat(),
            (y - wing * sin(radians + angle)).toFloat(),
            stroke
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val factor = min(width, height) / 48f
        if (factor <= 0f) return
        canvas.save()
        canvas.translate((width - 48f * factor) / 2f, (height - 48f * factor) / 2f)
        canvas.scale(factor, factor)
        val side = if (modifier.contains("left")) -1 else 1
        when {
            maneuverType == "arrive" -> {
                canvas.drawCircle(24f, 13f, 6f, fill)
                canvas.drawLine(24f, 22f, 24f, 41f, stroke)
            }
            modifier == "uturn" || maneuverType == "uturn" -> {
                path.reset()
                path.moveTo(31f, 42f)
                path.lineTo(31f, 20f)
                path.cubicTo(31f, 7f, 13f, 7f, 13f, 20f)
                path.lineTo(13f, 32f)
                canvas.drawPath(path, stroke)
                head(canvas, 13f, 32f, PI / 2)
            }
            maneuverType == "roundabout" || maneuverType == "rotary" -> {
                canvas.drawArc(RectF(12f, 12f, 36f, 36f), 95f, 290f, false, stroke)
                canvas.drawLine(24f, 44f, 24f, 36f, stroke)
                canvas.drawLine(35f, 18f, 41f, 12f, stroke)
                head(canvas, 41f, 12f, -PI / 4)
            }
            modifier == "straight" || modifier.isBlank() ||
                maneuverType == "depart" || maneuverType == "continue" -> {
                line(24f, 42f, 24f, 8f)
                canvas.drawPath(path, stroke)
                head(canvas, 24f, 8f, -PI / 2)
            }
            modifier.contains("slight") ||
                maneuverType == "merge" || maneuverType == "fork" -> {
                val x = 24f + side * 16f
                line(24f, 42f, 24f, 29f, x, 10f)
                canvas.drawPath(path, stroke)
                head(canvas, x, 10f, if (side > 0) -PI / 4 else -3 * PI / 4)
            }
            modifier.contains("sharp") -> {
                val x = 24f + side * 17f
                line(24f, 42f, 24f, 26f, x, 26f, x, 14f)
                canvas.drawPath(path, stroke)
                head(canvas, x, 14f, -PI / 2)
            }
            else -> {
                val x = 24f + side * 19f
                line(24f, 42f, 24f, 24f, x, 24f)
                canvas.drawPath(path, stroke)
                head(canvas, x, 24f, if (side > 0) 0.0 else PI)
            }
        }
        canvas.restore()
    }
}
