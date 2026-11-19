package com.moto.painel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Ícones originais do RAVO para os alertas viários.
 * Imagens são desenhadas em bitmap e adicionadas ao Style do MapLibre.
 */
object TrafficIcons {
    private const val RED = 0xFFFF333C.toInt()
    private const val YELLOW = 0xFFFFCC18.toInt()
    private const val DARK = 0xFF172135.toInt()

    // PNGs originais em assets/traffic_icons. Ajusta 1024px para 96px uma vez,
    // antes de enviar ao MapLibre, para evitar imagens grandes na memória/GPU.
    private val cached = mutableMapOf<String, Bitmap>()

    fun load(context: Context, type: String): Bitmap = synchronized(cached) {
        cached[type]?.let { return@synchronized it }
        val bitmap = try {
            val file = "traffic_icons/$type.png"
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(file).use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "PNG inválido" }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 96 &&
                bounds.outHeight / (sample * 2) >= 96) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val original = context.assets.open(file).use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: error("Não foi possível carregar $file")
            if (original.width == 96 && original.height == 96) original
            else Bitmap.createScaledBitmap(original, 96, 96, true).also { original.recycle() }
        } catch (_: Exception) {
            // Compatibilidade: se faltar um arquivo, o app continua com o desenho antigo.
            draw(type)
        }
        cached[type] = bitmap
        bitmap
    }

    // Placa de velocidade no padrão visual das placas circulares de trânsito.
    // A mesma arte é usada no radar e no indicador de limite da via.
    private val speedSignCache = mutableMapOf<Int, Bitmap>()

    fun speedSign(kmh: Int): Bitmap = synchronized(speedSignCache) {
        speedSignCache[kmh]?.let { return@synchronized it }
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        paint.color = 0x99000000.toInt()
        canvas.drawCircle(49f, 51f, 43f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(47f, 47f, 43f, paint)
        paint.color = 0xFFDE2F36.toInt()
        canvas.drawCircle(47f, 47f, 42f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(47f, 47f, 33.5f, paint)
        paint.color = 0xFF1C2430.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.25f
        canvas.drawCircle(47f, 47f, 33.5f, paint)
        paint.style = Paint.Style.FILL
        paint.typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
        )
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = if (kmh >= 100) 32f else 39f
        val text = kmh.toString()
        val baseline = 47f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, 47f, baseline, paint)
        speedSignCache[kmh] = bitmap
        bitmap
    }

    fun draw(type: String): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        fun fill(color: Int) { paint.color = color; paint.style = Paint.Style.FILL }
        fun stroke(color: Int, size: Float) { paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = size; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND }

        when (type) {
            "radar" -> {
                fill(Color.WHITE); canvas.drawCircle(48f, 48f, 44f, paint)
                fill(RED); canvas.drawCircle(48f, 48f, 40f, paint)
                // Corpo da câmera: silhueta simples reconhecível mesmo em mapa pequeno.
                fill(Color.WHITE); canvas.drawRoundRect(RectF(22f, 36f, 66f, 62f), 5f, 5f, paint)
                fill(Color.WHITE); canvas.drawRoundRect(RectF(29f, 30f, 48f, 41f), 3f, 3f, paint)
                val lens = Path().apply {
                    moveTo(64f, 42f); lineTo(76f, 35f)
                    lineTo(76f, 63f); lineTo(64f, 56f); close()
                }
                canvas.drawPath(lens, paint)
                fill(RED); canvas.drawCircle(45f, 49f, 10f, paint)
                fill(Color.WHITE); canvas.drawCircle(45f, 49f, 5f, paint)
            }
            "lombada" -> {
                val diamond = Path().apply {
                    moveTo(48f, 5f); lineTo(91f, 48f); lineTo(48f, 91f); lineTo(5f, 48f); close()
                }
                fill(DARK); canvas.drawPath(diamond, paint)
                val inner = Path().apply {
                    moveTo(48f, 12f); lineTo(84f, 48f); lineTo(48f, 84f); lineTo(12f, 48f); close()
                }
                fill(YELLOW); canvas.drawPath(inner, paint)
                stroke(DARK, 7f)
                canvas.drawLine(24f, 61f, 72f, 61f, paint)
                val hump = Path().apply {
                    moveTo(26f, 55f); cubicTo(34f, 55f, 36f, 36f, 48f, 36f)
                    cubicTo(60f, 36f, 62f, 55f, 70f, 55f)
                }
                canvas.drawPath(hump, paint)
            }
            "semaforo" -> {
                // Corpo do semáforo com as três luzes, sem indicar a fase atual.
                fill(Color.WHITE); canvas.drawRoundRect(RectF(29f, 7f, 67f, 89f), 11f, 11f, paint)
                fill(DARK); canvas.drawRoundRect(RectF(33f, 11f, 63f, 85f), 8f, 8f, paint)
                fill(0xFFFF3B3B.toInt()); canvas.drawCircle(48f, 28f, 10f, paint)
                fill(YELLOW); canvas.drawCircle(48f, 48f, 10f, paint)
                fill(0xFF33D97D.toInt()); canvas.drawCircle(48f, 68f, 10f, paint)
                stroke(0xFF0D1422.toInt(), 2f)
                for (y in floatArrayOf(28f, 48f, 68f)) canvas.drawCircle(48f, y, 10f, paint)
            }
        }
        return bitmap
    }
}
