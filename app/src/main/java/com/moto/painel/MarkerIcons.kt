package com.moto.painel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

// Ícones da sua posição no mapa, desenhados em um espaço de 100 x 100 e depois ampliados
fun styledMarker(style: Int, color: Int): Bitmap {
    val size = 120
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.scale(size / 100f, size / 100f)

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        setShadowLayer(4f, 0f, 2f, 0x88000000.toInt())
    }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeJoin = Paint.Join.ROUND
    }
    val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFF1A1A2E.toInt() }
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE }

    when (style) {
        0 -> {
            // Seta Waze
            val path = Path().apply {
                moveTo(50f, 8f); lineTo(88f, 88f); lineTo(50f, 70f); lineTo(12f, 88f); close()
            }
            c.drawPath(path, fill)
            c.drawPath(path, border)
        }
        1 -> {
            // Carro (vista de cima)
            val body = RectF(26f, 12f, 74f, 88f)
            c.drawRoundRect(body, 16f, 16f, fill)
            c.drawRoundRect(body, 16f, 16f, border)
            c.drawRoundRect(RectF(34f, 28f, 66f, 42f), 6f, 6f, dark)
            c.drawRoundRect(RectF(34f, 62f, 66f, 74f), 6f, 6f, dark)
            c.drawRoundRect(RectF(16f, 22f, 26f, 38f), 3f, 3f, dark)
            c.drawRoundRect(RectF(74f, 22f, 84f, 38f), 3f, 3f, dark)
            c.drawRoundRect(RectF(16f, 62f, 26f, 78f), 3f, 3f, dark)
            c.drawRoundRect(RectF(74f, 62f, 84f, 78f), 3f, 3f, dark)
        }
        2 -> {
            // Moto (vista de cima)
            c.drawCircle(50f, 22f, 10f, dark)
            c.drawCircle(50f, 78f, 11f, dark)
            c.drawRoundRect(RectF(43f, 30f, 57f, 70f), 7f, 7f, fill)
            c.drawRoundRect(RectF(43f, 30f, 57f, 70f), 7f, 7f, border)
            c.drawRoundRect(RectF(41f, 44f, 59f, 56f), 4f, 4f, dark)
            val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = dark.color
                style = Paint.Style.STROKE
                strokeWidth = 5f
                strokeCap = Paint.Cap.ROUND
            }
            c.drawLine(32f, 32f, 68f, 32f, bar)
            c.drawCircle(50f, 22f, 4f, white)
            c.drawCircle(50f, 78f, 5f, white)
        }
        3 -> {
            // Ponto GPS com halo
            c.drawCircle(50f, 50f, 46f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = (color and 0x00FFFFFF) or 0x44000000
            })
            c.drawCircle(50f, 50f, 24f, white)
            c.drawCircle(50f, 50f, 17f, fill)
        }
        else -> {
            // Pino de localização
            val pin = Path().apply {
                moveTo(50f, 94f); lineTo(24f, 56f); lineTo(76f, 56f); close()
            }
            c.drawPath(pin, fill)
            c.drawCircle(50f, 40f, 30f, fill)
            c.drawCircle(50f, 40f, 30f, border)
            c.drawCircle(50f, 40f, 11f, white)
        }
    }
    return bmp
}
