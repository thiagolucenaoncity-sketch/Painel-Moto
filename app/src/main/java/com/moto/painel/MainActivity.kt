package com.moto.painel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration as OsmConfiguration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.roundToInt

// ---------- Cores e helpers ----------

private val BLUE = 0xFF2EA8FF.toInt()
private val BLUE_DARK = 0xFF1B5FBF.toInt()
private val BLUE_LIGHT = 0xFF8FD8FF.toInt()
private val GREEN = 0xFF3DDC84.toInt()
private val YELLOW = 0xFFFFC107.toInt()
private val RED = 0xFFFF4D4D.toInt()
private val GRAY = 0xFF7F93B5.toInt()
private val CARD_BG = 0xCC0C1B33.toInt()
private val BORDER = 0xFF1F4E8C.toInt()
private val BTN_BG = 0xFF14284A.toInt()

private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private fun Context.px(v: Int): Int = (v * resources.displayMetrics.density).toInt()
private fun Context.pxF(v: Float): Float = v * resources.displayMetrics.density

private fun box(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = ctx.pxF(radiusDp.toFloat())
    if (strokeColor != null) setStroke(ctx.px(1), strokeColor)
}

private class Step(val lat: Double, val lon: Double, val text: String, val distanceM: Double)

// Desenha o ícone da sua posição no mapa, de acordo com a escolha do usuário
private fun markerBitmap(shape: Int, color: Int): Bitmap {
    val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE }
    when (shape) {
        0 -> {
            val path = Path().apply {
                moveTo(48f, 6f); lineTo(86f, 88f); lineTo(48f, 70f); lineTo(10f, 88f); close()
            }
            c.drawPath(path, fill)
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 4f
            })
        }
        1 -> {
            c.drawCircle(22f, 72f, 15f, white)
            c.drawCircle(74f, 72f, 15f, white)
            c.drawCircle(22f, 72f, 9f, fill)
            c.drawCircle(74f, 72f, 9f, fill)
            c.drawRoundRect(RectF(26f, 40f, 70f, 60f), 8f, 8f, fill)
            c.drawCircle(48f, 26f, 10f, fill)
        }
        2 -> {
            c.drawCircle(48f, 48f, 26f, white)
            c.drawCircle(48f, 48f, 19f, fill)
        }
        else -> {
            c.drawCircle(48f, 48f, 44f, fill)
            val arrow = Path().apply {
                moveTo(48f, 18f); lineTo(70f, 66f); lineTo(48f, 56f); lineTo(26f, 66f); close()
            }
            c.drawPath(arrow, white)
        }
    }
    return bmp
}

// ---------- Fundo com grade ----------

class GridView(ctx: Context) : View(ctx) {
    private val paint = Paint().apply { color = 0x1E2EA8FF; strokeWidth = 1f }

    override fun onDraw(canvas: Canvas) {
        val step = context.pxF(40f)
        var x = 0f
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            x += step
        }
        var y = 0f
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
            y += step
        }
    }
}

// ---------- Arco de velocidade ----------

class SpeedArcView(ctx: Context) : View(ctx) {
    var speed: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var accent: Int = BLUE

    private val rect = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF14284A.toInt()
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        val w = context.pxF(12f)
        trackPaint.strokeWidth = w
        arcPaint.strokeWidth = w
    }

    override fun onDraw(canvas: Canvas) {
        val pad = context.pxF(16f)
        val side = minOf(width, height) - pad * 2
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        rect.set(left, top, left + side, top + side)
        canvas.drawArc(rect, 135f, 270f, false, trackPaint)

        val frac = (speed / 200f).coerceIn(0f, 1f)
        if (frac > 0f) {
            arcPaint.color = when {
                speed > 120f -> RED
                speed > 80f -> YELLOW
                else -> accent
            }
            canvas.drawArc(rect, 135f, 270f * frac, false, arcPaint)
        }
    }
}

// ---------- Equalizador da música ----------

class EqualizerView(ctx: Context) : View(ctx) {
    var playing: Boolean = false
    var tint: Int = BLUE
        set(value) {
