package com.moto.painel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.min

/**
 * Modelos 5..10 usam os PNGs originais em assets/motorcycles.
 * 0..4 continuam com os marcadores anteriores.
 * O norte da imagem corresponde à frente da moto; a rotação é feita pelo MapLibre.
 */
fun ravoPositionMarker(context: Context, model: Int, color: Int, bagColor: Int = 0xFF2EA8FF.toInt()): Bitmap {
    if (model !in 5..10) return styledMarker(model, color)
    val files = arrayOf(
        "01_esportiva.png", "02_street.png", "03_trail.png",
        "04_scooter.png", "05_custom.png", "06_entrega.png"
    )
    val original = try {
        context.assets.open("motorcycles/" + files[model - 5]).use {
            BitmapFactory.decodeStream(it)
        }
    } catch (_: Exception) { null }
    if (original == null) return styledMarker(2, color)

    val size = 160
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val scale = min(148f / original.width, 148f / original.height)
    val w = original.width * scale
    val h = original.height * scale

    // Recolore apenas superfícies claras, mantendo pneus, motor, banco,
    // retrovisores, sombras e luzes originais.
    val pixels = IntArray(original.width * original.height)
    original.getPixels(pixels, 0, original.width, 0, 0, original.width, original.height)
    val hsv = FloatArray(3)
    val target = FloatArray(3)
    Color.colorToHSV(color, target)
    for (i in pixels.indices) {
        val p = pixels[i]
        if (Color.alpha(p) < 12) continue
        Color.colorToHSV(p, hsv)
        val brightness = hsv[2]
        val saturation = hsv[1]
        if (model == 10) {
            // No modelo de entrega, moto e bag têm cores independentes.
            // As áreas azuis da metade traseira pertencem à bag.
            val isBlue = hsv[0] in 175f..270f && saturation > 0.23f && brightness > 0.13f
            if (isBlue) {
                val y = i / original.width
                val selected = if (y > original.height * 0.52f) bagColor else color
                val chosen = FloatArray(3)
                Color.colorToHSV(selected, chosen)
                hsv[0] = chosen[0]
                hsv[1] = chosen[1]
                hsv[2] = (brightness * (0.30f + 0.70f * chosen[2])).coerceIn(0f, 1f)
                pixels[i] = Color.HSVToColor(Color.alpha(p), hsv)
            }
        } else if (brightness > 0.68f && saturation < 0.19f) {
            hsv[0] = target[0]
            hsv[1] = target[1] * 0.82f
            hsv[2] = (brightness * (0.72f + target[2] * 0.28f)).coerceIn(0f, 1f)
            pixels[i] = Color.HSVToColor(Color.alpha(p), hsv)
        }
    }
    val recolored = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
    recolored.setPixels(pixels, 0, original.width, 0, 0, original.width, original.height)
    canvas.drawBitmap(recolored, null,
        android.graphics.RectF((size - w) / 2, (size - h) / 2,
            (size + w) / 2, (size + h) / 2), paint)
    recolored.recycle()
    original.recycle()
    return output
}
