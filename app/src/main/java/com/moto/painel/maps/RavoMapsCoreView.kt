package com.moto.painel.maps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max

/**
 * Primeiro renderizador do RAVO Maps Core.
 * ATENÇÃO: geometria fictícia, marcador central ilustrativo, SEM navegação.
 * Testar desempenho/pan/zoom; nunca utilizar como GPS durante a pilotagem.
 */
class RavoMapsCoreView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val dark = Color.rgb(7, 20, 36)
    private val blocks = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(17, 38, 60) }
    private val streets = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(52, 81, 113); strokeWidth = 16f * density
        strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 135, 32); strokeWidth = 27f * density
        strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    private val avenue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 142, 34); strokeWidth = 12f * density
        strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(176, 192, 209); textSize = 11f * density
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG)
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private val scale = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(0.6f, 3.5f)
                invalidate()
                return true
            }
        })
    private val gestures = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                offsetX -= distanceX
                offsetY -= distanceY
                invalidate()
                return true
            }
        })

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(dark)
        val saved = canvas.save()
        canvas.translate(width / 2f + offsetX, height / 2f + offsetY)
        canvas.scale(zoom, zoom)
        val step = 90f * density

        for (x in -5..5) for (y in -7..7) {
            canvas.drawRoundRect(
                RectF(x * step + 9f * density, y * step + 9f * density,
                    (x + 1) * step - 9f * density, (y + 1) * step - 9f * density),
                7f * density, 7f * density, blocks
            )
        }
        for (x in -5..6) canvas.drawLine(x * step, -7 * step, x * step, 8 * step, streets)
        for (y in -7..8) canvas.drawLine(-5 * step, y * step, 6 * step, y * step, streets)

        val mainRoad = Path().apply {
            moveTo(-5f * step, 3.5f * step)
            cubicTo(-2f * step, 1.5f * step, -1f * step, -step, step, -step)
            cubicTo(3f * step, -step, 3.5f * step, -4f * step, 6f * step, -5f * step)
        }
        canvas.drawPath(mainRoad, glow)
        canvas.drawPath(mainRoad, avenue)
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("AVENIDA RAVO (DEMONSTRAÇÃO)", 0f, -1.45f * step, text)
        canvas.restoreToCount(saved)

        // Marcador deliberadamente fixo: NÃO corresponde à localização real.
        val cx = width / 2f
        val cy = height / 2f
        marker.color = Color.argb(70, 255, 145, 35)
        canvas.drawCircle(cx, cy, 23f * density, marker)
        marker.color = Color.rgb(255, 145, 35)
        canvas.drawCircle(cx, cy, 9f * density, marker)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("RAVO Maps Core • protótipo 2D", 14f * density, 30f * density, text)
        canvas.drawText("MAPA FICTÍCIO — NÃO USAR PARA NAVEGAR", 14f * density, 49f * density, detail)
        canvas.drawText("Arraste e use dois dedos para aproximar", 14f * density,
            max(70f * density, height - 19f * density), detail)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        if (!scale.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
