package com.moto.painel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
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
import android.view.MotionEvent
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
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------- Constantes ----------

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val EMPTY_GEO = "{\"type\":\"FeatureCollection\",\"features\":[]}"

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

// Desenha o ícone da sua posição, de acordo com a escolha do usuário
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
            field = value
            paint.color = value
            invalidate()
        }
    private val levels = FloatArray(5) { 0.15f }
    private val rnd = Random()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE }
    private val rect = RectF()

    fun step() {
        for (i in levels.indices) {
            levels[i] = if (playing) 0.2f + rnd.nextFloat() * 0.8f else 0.15f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = levels.size
        val barW = width / (n * 2 - 1f)
        for (i in 0 until n) {
            val x = i * 2 * barW
            val top = height * (1f - levels[i])
            rect.set(x, top, x + barW, height.toFloat())
            canvas.drawRoundRect(rect, barW / 2, barW / 2, paint)
        }
    }
}

// ---------- Tela principal: painel + mapa ----------

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null

    // Escolhas do usuário, aplicadas ao montar a tela
    private var accent = BLUE
    private var arcColor = BLUE
    private var numColor = Color.WHITE
    private var unitColor = GRAY
    private var units = 0
    private var appliedVer = -1

    // Orientação: 0 = automático, 1 = paisagem fixa, 2 = retrato fixo
    private var orientMode = 0

    // Mapa 3D (inclinado com prédios) ou 2D (visão de cima)
    private var is3D = false
    private val modeBtns = ArrayList<Button>()

    // Seguir a posição: fica falso quando o usuário arrasta o mapa
    private var followMe = true

    // Todos os mapas (miniatura e mapa completo)
    private val holders = ArrayList<MapHolder>()

    private var showMap = false
    private var locationStarted = false
    private var firstFixDone = false

    // Player flutuante (arrastável)
    private var musicOpen = false
    private lateinit var musicFab: Button
    private lateinit var musicPopup: LinearLayout
    private var fabDX = 0f
    private var fabDY = 0f
    private var fabDownX = 0f
    private var fabDownY = 0f
    private var fabMoved = false

    // Painel
    private lateinit var panelScreen: LinearLayout
    private lateinit var minimap: MapView
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var gpsView: TextView
    private lateinit var vehicleView: TextView
    private lateinit var speedView: TextView
    private lateinit var unitView: TextView
    private lateinit var arcView: SpeedArcView
    private lateinit var navView: TextView
    private lateinit var distView: TextView
    private lateinit var timeView: TextView
    private lateinit var maxView: TextView
    private lateinit var tripBtn: Button
    private lateinit var artView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var eqView: EqualizerView
    private lateinit var playBtn: Button

    // Mapa completo
    private lateinit var mapScreen: LinearLayout
    private lateinit var fullMap: MapView
    private lateinit var searchInput: EditText
    private lateinit var mapInstructionView: TextView
    private lateinit var mapInfoView: TextView
    private lateinit var voiceBtn: Button

    // Navegação
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voiceOn = true
    private var lastFix: Location? = null
    private var steps: List<Step> = emptyList()
    private var stepIndex = 0
    private var announcedNear = false
    private var routePoints: List<LatLng> = emptyList()
    private var routeDest: LatLng? = null
    private var routeName = ""

    // Viagem (valores internos sempre em km/h e metros)
    private var tripLast: Location? = null
    private var totalM = 0.0
    private var maxSpeed = 0
    private var tripRunning = false
    private var tripStart = 0L
    private var tripElapsed = 0L

    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEE, dd MMM", Locale("pt", "BR"))

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clockView.text = clockFmt.format(now)
            dateView.text = dateFmt.format(now)
            updateTime()
            handler.postDelayed(this, 1000)
        }
    }

    private val pollSessions = object : Runnable {
        override fun run() {
            bindActiveSession()
            handler.postDelayed(this, 2000)
        }
    }

    private val eqTick = object : Runnable {
        override fun run() {
            eqView.step()
            handler.postDelayed(this, 150)
        }
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateMedia()
        override fun onPlaybackStateChanged(state: PlaybackState?) = updateMedia()
    }

    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val kmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
            val shown = if (kmh < 2f) 0 else kmh.roundToInt()

            speedView.text = speedText(shown)
            arcView.speed = shown.toFloat()
            if (shown > maxSpeed) maxSpeed = shown
            maxView.text = speedText(maxSpeed)
            setGps("● GPS ok (±${loc.accuracy.roundToInt()} m)", GREEN)

            if (tripRunning) {
                tripLast?.let { prev ->
                    val d = prev.distanceTo(loc)
                    if (d in 3f..500f) totalM += d
                }
                distView.text = distText(totalM)
            }
            tripLast = loc
            lastFix = loc

            // Primeira localização: já posiciona o mapa
            if (!firstFixDone) {
                firstFixDone = true
                followMe = true
                holders.forEach { followCamera(it, loc, false) }
            } else if (followMe) {
                holders.forEach { followCamera(it, loc, true) }
            }
            holders.forEach { updateMeMarker(it, loc) }
            onFix(loc)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) = setGps("● GPS desligado", RED)

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    // Toque no botão ♪: arrastar move o botão; um toque simples abre ou fecha o player
    private val fabTouch = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fabDX = v.translationX - e.rawX
                fabDY = v.translationY - e.rawY
                fabDownX = e.rawX
                fabDownY = e.rawY
                fabMoved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.rawX - fabDownX) > 12 || abs(e.rawY - fabDownY) > 12) fabMoved = true
                if (fabMoved) {
                    val parent = v.parent as? View
                    val minX = -((parent?.width ?: 0) - v.width).toFloat()
                    val minY = -((parent?.height ?: 0) - v.height).toFloat()
                    val maxX = px(16).toFloat()
                    val maxY = px(16).toFloat()
                    v.translationX = (e.rawX + fabDX).coerceIn(minX, maxX)
                    v.translationY = (e.rawY + fabDY).coerceIn(minY, maxY)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (fabMoved) {
                    Prefs.putRaw(this, "fab_x", v.translationX.roundToInt())
                    Prefs.putRaw(this, "fab_y", v.translationY.roundToInt())
                } else {
                    v.performClick()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> true
            else -> false
        }
    }

    // Um mapa: MapView + estilo + camadas (posição, rota, destino e prédios 3D)
    private inner class MapHolder(val interactive: Boolean) {
        val view: MapView = MapView(this@MainActivity)
        var map: MapLibreMap? = null
        var style: Style? = null
        var meSrc: GeoJsonSource? = null
        var routeSrc: GeoJsonSource? = null
        var destSrc: GeoJsonSource? = null
        var buildings: FillExtrusionLayer? = null

        init {
            view.onCreate(null)
            view.onStart()
            view.onResume()
            view.getMapAsync { m ->
                map = m
                m.uiSettings.setAllGesturesEnabled(interactive)
                if (interactive) {
                    m.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            followMe = false
                        }
                    }
                }
                m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s -> onStyleReady(this, s) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MapLibre.getInstance(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("pt", "BR"))
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

        applyOrientation()
        setContentView(buildUi())

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocation()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    // Chamado quando o celular gira (o manifesto impede o reinício do app)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setContentView(buildUi())
        updateMedia()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startLocation()
        } else {
            setGps("● Sem permissão de localização", RED)
        }
    }

    override fun onStart() {
        super.onStart()
        holders.forEach { it.view.onStart() }
    }

    override fun onResume() {
        super.onResume()
        // Se mudou alguma configuração na tela de Configurações, redesenha a tela principal
        if (Prefs.version(this) != appliedVer) {
            setContentView(buildUi())
            updateMedia()
        }
        holders.forEach { it.view.onResume() }
        handler.post(tick)
        handler.post(pollSessions)
        handler.post(eqTick)
    }

    override fun onPause() {
        super.onPause()
        holders.forEach { it.view.onPause() }
        handler.removeCallbacksAndMessages(null)
    }

    override fun onStop() {
        holders.forEach { it.view.onStop() }
        super.onStop()
    }

    override fun onDestroy() {
        locationManager.removeUpdates(locationListener)
        controller?.unregisterCallback(mediaCallback)
        tts?.shutdown()
        destroyHolders()
        super.onDestroy()
    }

    private fun destroyHolders() {
        holders.forEach {
            it.view.onPause()
            it.view.onStop()
            it.view.onDestroy()
        }
        holders.clear()
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!locationStarted) {
            locationStarted = true
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
        }
        setGps("● GPS: aguardando sinal", GRAY)
    }

    private fun setGps(text: String, color: Int) {
        gpsView.text = text
        gpsView.setTextColor(color)
    }

    // ---------- Mapa: criação, estilo e camadas ----------

    private fun newMap(interactive: Boolean): MapView {
        val h = MapHolder(interactive)
        holders.add(h)
        return h.view
    }

    private fun onStyleReady(h: MapHolder, s: Style) {
        h.style = s
        s.addImage("me-icon", markerBitmap(Prefs.getInt(this, "marker"), accent))

        val rs = GeoJsonSource("route-src")
        val ds = GeoJsonSource("dest-src")
        val ms = GeoJsonSource("me-src")
        h.routeSrc = rs
        h.destSrc = ds
        h.meSrc = ms
        s.addSource(rs)
        s.addSource(ds)
        s.addSource(ms)

        // Prédios em 3D (camada extra por cima do mapa)
        try {
            val b = FillExtrusionLayer("building-3d", "openmaptiles")
                .withSourceLayer("building")
                .withProperties(
                    PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
                    PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
                    PropertyFactory.fillExtrusionColor(0xFF2A3F63.toInt()),
                    PropertyFactory.fillExtrusionOpacity(0.9f),
                    PropertyFactory.visibility(if (is3D) Property.VISIBLE else Property.NONE)
                )
            s.addLayer(b)
            h.buildings = b
        } catch (e: Exception) {
            // Se o estilo não tiver a camada de prédios, o mapa continua em 2D
        }

        s.addLayer(
            LineLayer("route-layer", "route-src").withProperties(
                PropertyFactory.lineColor(accent),
                PropertyFactory.lineWidth(8f)
            )
        )
        s.addLayer(
            CircleLayer("dest-layer", "dest-src").withProperties(
                PropertyFactory.circleColor(accent),
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleStrokeColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(2f)
            )
        )
        s.addLayer(
            SymbolLayer("me-layer", "me-src").withProperties(
                PropertyFactory.iconImage("me-icon"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotate(Expression.get("bearing"))
            )
        )

        refreshRoute(h)
        lastFix?.let { loc ->
            updateMeMarker(h, loc)
            followCamera(h, loc, false)
        }
        // Garante que o mapa já abre na inclinação escolhida
        h.map?.let { m ->
            m.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(m.cameraPosition).tilt(if (is3D) 60.0 else 0.0).build()
                )
            )
        }
    }

    // Posiciona a câmera sobre você. Em 3D, o mapa gira com a direção do movimento.
    private fun followCamera(h: MapHolder, loc: Location, animate: Boolean) {
        val m = h.map ?: return
        val zoom = if (h.interactive) 17.0 else 16.0
        val bearing = when {
            !is3D -> 0.0
            loc.hasBearing() && loc.speed > 1f -> loc.bearing.toDouble()
            else -> m.cameraPosition.bearing
        }
        val pos = CameraPosition.Builder()
            .target(LatLng(loc.latitude, loc.longitude))
            .zoom(zoom)
            .tilt(if (is3D) 60.0 else 0.0)
            .bearing(bearing)
            .build()
        if (animate) {
            m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 800)
        } else {
            m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
        }
    }

    private fun updateMeMarker(h: MapHolder, loc: Location) {
        val src = h.meSrc ?: return
        val f = Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude))
        val heading = if (loc.hasBearing() && loc.speed > 1f) loc.bearing.toDouble() else 0.0
        f.addNumberProperty("bearing", heading)
        src.setGeoJson(f)
    }

    private fun refreshRoute(h: MapHolder) {
        val rs = h.routeSrc ?: return
        val ds = h.destSrc ?: return
        if (routePoints.size < 2) {
            rs.setGeoJson(EMPTY_GEO)
        } else {
            val pts = routePoints.map { Point.fromLngLat(it.longitude, it.latitude) }
            rs.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)))
        }
        val d = routeDest
        if (d == null) {
            ds.setGeoJson(EMPTY_GEO)
        } else {
            ds.setGeoJson(Feature.fromGeometry(Point.fromLngLat(d.longitude, d.latitude)))
        }
    }

    // Alterna entre visão 2D (de cima) e 3D (inclinada, com prédios)
    private fun toggle3D() {
        is3D = !is3D
        holders.forEach { h ->
            h.buildings?.setProperties(PropertyFactory.visibility(if (is3D) Property.VISIBLE else Property.NONE))
            val m = h.map
            if (m != null) {
                if (followMe) {
                    lastFix?.let { followCamera(h, it, true) }
                } else {
                    m.easeCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder(m.cameraPosition).tilt(if (is3D) 60.0 else 0.0).build()
                        )
                    )
                }
            }
        }
        modeBtns.forEach { it.text = modeLabel() }
    }

    private fun modeLabel(): String = if (is3D) "2D" else "3D"

    // Centraliza o mapa completo na sua posição e volta a seguir
    private fun recenter() {
        val loc = lastFix
        if (loc == null) {
            toast("Aguardando sinal do GPS...")
            return
        }
        followMe = true
        holders.forEach { followCamera(it, loc, true) }
    }

    // ---------- Orientação ----------

    private fun applyOrientation() {
        requestedOrientation = when (orientMode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun orientLabel(): String = when (orientMode) {
        1 -> "⬌"
        2 -> "⬍"
        else -> "⟳"
    }

    // ---------- Unidades ----------

    private fun speedText(kmh: Int): String =
        if (units == 1) (kmh * 0.621371).roundToInt().toString() else kmh.toString()

    private fun unitSpeed(): String = if (units == 1) "mph" else "km/h"

    private fun distText(m: Double): String =
        if (units == 1) String.format(Locale.US, "%.2f mi", m / 1609.344)
        else String.format(Locale.US, "%.2f km", m / 1000.0)

    // ---------- Telas ----------

    private fun setScreen(map: Boolean) {
        showMap = map
        panelScreen.visibility = if (map) View.GONE else View.VISIBLE
        mapScreen.visibility = if (map) View.VISIBLE else View.GONE
        musicFab.visibility = if (map) View.GONE else View.VISIBLE
        musicPopup.visibility = if (map || !musicOpen) View.GONE else View.VISIBLE
    }

    private fun toggleMusic() {
        musicOpen = !musicOpen
        musicPopup.visibility = if (musicOpen) View.VISIBLE else View.GONE
        musicFab.text = if (musicOpen) "✕" else "♪"
    }

    // ---------- Navegação ----------

    private fun onFix(loc: Location) {
        if (steps.isEmpty() || stepIndex >= steps.size) return
        val s = steps[stepIndex]
        val d = distance(loc.latitude, loc.longitude, s.lat, s.lon)

        if (d < 40) {
            stepIndex++
            announcedNear = false
            if (stepIndex < steps.size) {
                val next = steps[stepIndex]
                showInstruction(next.text, "")
                say(next.text)
            } else {
                showInstruction("Você chegou ao destino", "")
                say("Você chegou ao destino")
            }
            return
        }

        if (!announcedNear && d < 300) {
            announcedNear = true
            say("Em ${fmtDist(d)}, ${s.text}")
        }
        val remaining = d + steps.drop(stepIndex).sumOf { it.distanceM }
        showInstruction("${s.text} · em ${fmtDist(d)}", "Faltam ${fmtDist(remaining)}")
    }

    // Mostra a instrução no mapa completo e na linha de navegação do painel
    private fun showInstruction(main: String, sub: String) {
        mapInstructionView.text = main
        mapInfoView.text = sub
        navView.text = "➜ $main"
        navView.visibility = if (main.isEmpty()) View.GONE else View.VISIBLE
        navView.isSelected = true
    }

    private fun go() {
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) return
        val origin = lastFix
        if (origin == null) {
            toast("Aguardando sinal do GPS...")
            return
        }
        toast("Buscando rota...")

        Thread {
            try {
                val geo = JSONArray(
                    getText(
                        "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" +
                            URLEncoder.encode(q, "UTF-8")
                    )
                )
                if (geo.length() == 0) {
                    handler.post { toast("Endereço não encontrado") }
                    return@Thread
                }
                val hit = geo.getJSONObject(0)
                val destLat = hit.getDouble("lat")
                val destLon = hit.getDouble("lon")
                val name = hit.optString("display_name", q).take(60)

                val url = "https://router.project-osrm.org/route/v1/driving/" +
                    "${origin.longitude},${origin.latitude};$destLon,$destLat" +
                    "?overview=full&geometries=geojson&steps=true"
                val res = JSONObject(getText(url))
                if (res.optString("code") != "Ok") {
                    handler.post { toast("Não achei rota até lá") }
                    return@Thread
                }

                val route = res.getJSONArray("routes").getJSONObject(0)
                val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
                val points = ArrayList<LatLng>()
                for (i in 0 until coords.length()) {
                    val c = coords.getJSONArray(i)
                    points.add(LatLng(c.getDouble(1), c.getDouble(0)))
                }

                val list = ArrayList<Step>()
                val legs = route.getJSONArray("legs")
                for (l in 0 until legs.length()) {
                    val st = legs.getJSONObject(l).getJSONArray("steps")
                    for (i in 0 until st.length()) {
                        val s = st.getJSONObject(i)
                        val man = s.getJSONObject("maneuver")
                        val loc = man.getJSONArray("location")
                        list.add(
                            Step(
                                loc.getDouble(1),
                                loc.getDouble(0),
                                describe(man, s.optString("name")),
                                s.getDouble("distance")
                            )
                        )
                    }
                }

                val total = route.getDouble("distance")
                val dur = route.getDouble("duration")
                handler.post {
                    showRoute(points, list, LatLng(destLat, destLon), name, total, dur)
                }
            } catch (e: Exception) {
                handler.post { toast("Não consegui calcular a rota. Verifique a internet.") }
            }
        }.start()
    }

    private fun showRoute(
        points: List<LatLng>,
        list: List<Step>,
        dest: LatLng,
        name: String,
        totalM: Double,
        durS: Double
    ) {
        routePoints = points
        routeDest = dest
        routeName = name
        steps = list
        announcedNear = false
        // O primeiro passo começa no seu ponto atual, então pulamos ele
        stepIndex = if (list.size > 1) 1 else 0

        holders.forEach { refreshRoute(it) }

        // Mostra a rota inteira no mapa grande (para de seguir até você tocar em ◎)
        followMe = false
        val full = holders.firstOrNull { it.interactive }?.map
        if (full != null && points.size > 1) {
            val bounds = LatLngBounds.Builder().includes(points).build()
            full.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
        }

        if (list.isNotEmpty()) {
            val first = list[stepIndex]
            showInstruction(first.text, "Total ${fmtDist(totalM)} · cerca de ${(durS / 60).roundToInt()} min")
            say(first.text)
        }
    }

    private fun clearRoute() {
        routePoints = emptyList()
        routeDest = null
        steps = emptyList()
        stepIndex = 0
        holders.forEach { refreshRoute(it) }
        mapInstructionView.text = "Digite um destino acima"
        mapInfoView.text = ""
        navView.text = ""
        navView.visibility = View.GONE
    }

    // Traduz a manobra do OSRM para uma frase em português
    private fun describe(man: JSONObject, road: String): String {
        val type = man.optString("type")
        val mod = man.optString("modifier")
        val onRoad = if (road.isNotBlank()) " na $road" else ""
        val side = when (mod) {
            "left" -> "à esquerda"
            "right" -> "à direita"
            "slight left" -> "levemente à esquerda"
            "slight right" -> "levemente à direita"
            "sharp left" -> "forte à esquerda"
            "sharp right" -> "forte à direita"
            else -> ""
        }
        return when {
            type == "arrive" -> "Você chegou ao destino"
            type == "depart" -> if (road.isNotBlank()) "Siga pela $road" else "Siga em frente"
            mod == "uturn" -> "Faça o retorno$onRoad"
            type == "roundabout" || type == "rotary" -> "Entre na rotatória$onRoad"
            type == "turn" && mod == "straight" -> "Siga em frente$onRoad"
            type == "turn" -> "Vire $side$onRoad"
            type == "fork" -> "Mantenha-se $side$onRoad"
            type == "end of road" -> "Ao fim da via, vire $side$onRoad"
            type == "off ramp" -> "Saia $side$onRoad"
            type == "on ramp" -> "Entre $side$onRoad"
            type == "merge" -> "Entre na via$onRoad"
            else -> "Continue$onRoad"
        }.replace("  ", " ").trim()
    }

    private fun say(text: String) {
        if (voiceOn && ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun fmtDist(m: Double): String =
        if (m < 1000) "${m.roundToInt()} m"
        else String.format(Locale("pt", "BR"), "%.1f km", m / 1000)

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, r)
        return r[0].toDouble()
    }

    private fun getText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "PainelMotoApp/1.0 (uso pessoal)")
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    // ---------- Viagem ----------

    private fun elapsedMs(): Long =
        if (tripRunning) tripElapsed + (System.currentTimeMillis() - tripStart) else tripElapsed

    private fun updateTime() {
        val s = elapsedMs() / 1000
        timeView.text = String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private fun toggleTrip() {
        if (tripRunning) {
            tripElapsed = elapsedMs()
            tripRunning = false
            tripBtn.text = "▶ Retomar"
        } else {
            tripStart = System.currentTimeMillis()
            tripRunning = true
            tripBtn.text = "❚❚ Pausar"
        }
    }

    private fun resetTrip() {
        tripRunning = false
        tripElapsed = 0L
        totalM = 0.0
        maxSpeed = 0
        tripLast = null
        tripBtn.text = "▶ Iniciar"
        distView.text = distText(0.0)
        maxView.text = "0"
        timeView.text = "00:00:00"
    }

    // ---------- Música ----------

    private fun notificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    private fun bindActiveSession() {
        if (!notificationAccessGranted()) {
            updateMedia()
            return
        }
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val sessions = try {
            msm.getActiveSessions(ComponentName(this, MusicListenerService::class.java))
        } catch (e: SecurityException) {
            emptyList()
        }
        val newCtrl = sessions.firstOrNull()
        if (newCtrl?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(mediaCallback)
            controller = newCtrl
            controller?.registerCallback(mediaCallback, handler)
        }
        updateMedia()
    }

    private fun updateMedia() {
        val c = controller
        if (c == null) {
            val granted = notificationAccessGranted()
            titleView.text = if (granted) "Nada tocando" else "Toque aqui para liberar a música"
            artistView.text = if (granted) "Dê play no Spotify" else ""
            artView.setImageBitmap(null)
            playBtn.text = "▶"
            eqView.playing = false
            return
        }
        val md = c.metadata
        titleView.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Sem título"
        artistView.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        artView.setImageBitmap(art)
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        playBtn.text = if (playing) "⏸" else "▶"
        eqView.playing = playing
    }

    private fun control(action: (MediaController.TransportControls) -> Unit) {
        controller?.transportControls?.let(action)
    }

    // ---------- Montagem da interface ----------

    private fun label(
        size: Float,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false,
        align: Int = Gravity.START
    ): TextView = TextView(this).apply {
        textSize = size
        setTextColor(color)
        gravity = align
        typeface = when {
            mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            mono -> Typeface.MONOSPACE
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
    }

    private fun pill(caption: String, bg: Int, fg: Int, sizeSp: Float = 16f): Button = Button(this).apply {
        text = caption
        textSize = sizeSp
        setTextColor(fg)
        isAllCaps = false
        stateListAnimator = null
        background = box(context, bg, 22)
        minHeight = 0
        minimumHeight = 0
    }

    // Botão pequeno quadrado, para ícones
    private fun miniBtn(caption: String): Button = Button(this).apply {
        text = caption
        textSize = 16f
        setTextColor(Color.WHITE)
        isAllCaps = false
        stateListAnimator = null
        background = box(context, BTN_BG, 12, BORDER)
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
    }

    private fun statCard(title: String, value: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = box(context, CARD_BG, 14, BORDER)
        setPadding(px(6), px(8), px(6), px(8))
        addView(label(11f, GRAY, align = Gravity.CENTER).apply { text = title })
        addView(value, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun buildUi(): FrameLayout {
        // Desfaz os mapas antigos antes de montar a tela de novo
        destroyHolders()
        modeBtns.clear()

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // Lê as escolhas salvas na tela de Configurações
        accent = Prefs.accentColor(this)
        arcColor = Prefs.colorOf(this, "arc_color", BLUE)
        numColor = Prefs.colorOf(this, "num_color", Color.WHITE)
        unitColor = Prefs.colorOf(this, "unit_color", GRAY)
        units = Prefs.getInt(this, "units")
        appliedVer = Prefs.version(this)
        val bgStyle = Prefs.getInt(this, "bg")

        val frame = FrameLayout(this).apply {
            background = when (bgStyle) {
                1 -> GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt())
                )
                2 -> GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xFF0B3A6E.toInt(), 0xFF02060F.toInt())
                )
                3 -> GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xFF3A1B6E.toInt(), 0xFF06040F.toInt())
                )
                else -> GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xFF06142B.toInt(), 0xFF02060F.toInt())
                )
            }
        }
        if (bgStyle == 0) frame.addView(GridView(this), FrameLayout.LayoutParams(MATCH, MATCH))

        panelScreen = buildPanel(landscape)
        mapScreen = buildMapScreen()
        frame.addView(panelScreen, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(mapScreen, FrameLayout.LayoutParams(MATCH, MATCH))

        // Player: painel que abre (acima do botão) e botão flutuante arrastável
        frame.addView(
            musicPopup,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
                setMargins(px(12), 0, px(12), px(84))
            }
        )
        frame.addView(
            musicFab,
            FrameLayout.LayoutParams(px(56), px(56), Gravity.END or Gravity.BOTTOM).apply {
                setMargins(0, 0, px(16), px(16))
            }
        )

        setScreen(showMap)
        return frame
    }

    private fun buildPanel(landscape: Boolean): LinearLayout {
        val gaugeStyle = Prefs.getInt(this, "gauge")
        val vehicleName = listOf(Prefs.getStr(this, "brand"), Prefs.getStr(this, "model"))
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val root = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(16))
        }

        // Barra superior: relógio, GPS e data
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        clockView = label(20f, BLUE_LIGHT, bold = true, mono = true)
        gpsView = label(12f, GRAY, align = Gravity.CENTER)
        dateView = label(12f, GRAY, align = Gravity.END)
        topBar.addView(clockView, LinearLayout.LayoutParams(0, WRAP, 1f))
        topBar.addView(gpsView, LinearLayout.LayoutParams(0, WRAP, 1.6f))
        topBar.addView(dateView, LinearLayout.LayoutParams(0, WRAP, 1f))

        // Veículo escolhido nas Configurações (some se estiver vazio)
        vehicleView = label(15f, accent, bold = true, align = Gravity.CENTER).apply {
            text = vehicleName
            visibility = if (vehicleName.isEmpty()) View.GONE else View.VISIBLE
        }

        // Velocímetro: miniatura do mapa ao fundo, arco e velocidade por cima
        minimap = newMap(false)
        minimap.alpha = 0.35f
        arcView = SpeedArcView(this).apply { this.accent = this@MainActivity.arcColor }
        arcView.visibility = if (gaugeStyle == 1) View.GONE else View.VISIBLE
        val digital = gaugeStyle == 1
        speedView = label(
            if (digital) (if (landscape) 110f else 120f) else (if (landscape) 64f else 84f),
            numColor,
            bold = true,
            align = Gravity.CENTER
        ).apply {
            text = "0"
            maxLines = 1
        }
        unitView = label(16f, unitColor, align = Gravity.CENTER).apply { text = unitSpeed() }
        val centerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(speedView, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(unitView, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val arcBlock = FrameLayout(this).apply {
            addView(minimap, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(arcView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(centerCol, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        // Linha de navegação: fica numa linha só e desliza se for longa
        navView = label(15f, BLUE_LIGHT, bold = true, align = Gravity.CENTER).apply {
            visibility = View.GONE
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            isSelected = true
        }

        // Cartões de estatística (valores sempre em uma linha)
        val statSize = if (landscape) 16f else 20f
        distView = label(statSize, Color.WHITE, bold = true, mono = true, align = Gravity.CENTER).apply {
            text = distText(0.0)
            maxLines = 1
        }
        timeView = label(statSize, Color.WHITE, bold = true, mono = true, align = Gravity.CENTER).apply {
            text = "00:00:00"
            maxLines = 1
        }
        maxView = label(statSize, Color.WHITE, bold = true, mono = true, align = Gravity.CENTER).apply {
            text = "0"
            maxLines = 1
        }
        val statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statsRow.addView(statCard("VIAGEM", distView), LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(px(4), 0, px(4), 0) })
        statsRow.addView(statCard("TEMPO", timeView), LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(px(4), 0, px(4), 0) })
        statsRow.addView(statCard("MÁX.", maxView), LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(px(4), 0, px(4), 0) })

        // Botões da viagem
        tripBtn = pill("▶ Iniciar", accent, Color.BLACK, 15f)
        tripBtn.setOnClickListener { toggleTrip() }
        val resetBtn = pill("Zerar", BTN_BG, GRAY, 15f)
        resetBtn.setOnClickListener { resetTrip() }
        val tripRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tripRow.addView(tripBtn, LinearLayout.LayoutParams(0, px(44), 1f).apply { setMargins(px(4), 0, px(4), 0) })
        tripRow.addView(resetBtn, LinearLayout.LayoutParams(0, px(44), 1f).apply { setMargins(px(4), 0, px(4), 0) })

        // Player: capa, título e artista (versão menor)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        artView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = box(context, BTN_BG, 10)
            clipToOutline = true
        }
        titleView = label(15f, Color.WHITE, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        artistView = label(12f, GRAY).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleView.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), 0, px(6), 0)
            addView(titleView, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(artistView, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        eqView = EqualizerView(this).apply { tint = accent }
        card.addView(artView, LinearLayout.LayoutParams(px(48), px(48)))
        card.addView(textCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(eqView, LinearLayout.LayoutParams(px(32), px(24)))

        // Controles de música (versão menor)
        val prevBtn = pill("⏮", BTN_BG, Color.WHITE, 18f)
        prevBtn.setOnClickListener { control { it.skipToPrevious() } }
        playBtn = pill("▶", accent, Color.BLACK, 22f)
        playBtn.setOnClickListener {
            control {
                if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) it.pause() else it.play()
            }
        }
        val nextBtn = pill("⏭", BTN_BG, Color.WHITE, 18f)
        nextBtn.setOnClickListener { control { it.skipToNext() } }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(prevBtn, LinearLayout.LayoutParams(0, px(48), 1f).apply { setMargins(px(4), 0, px(4), 0) })
        controls.addView(playBtn, LinearLayout.LayoutParams(0, px(48), 1.6f).apply { setMargins(px(4), 0, px(4), 0) })
        controls.addView(nextBtn, LinearLayout.LayoutParams(0, px(48), 1f).apply { setMargins(px(4), 0, px(4), 0) })

        // Painel do player, escondido até tocar no botão ♪
        musicPopup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(context, CARD_BG, 18, BORDER)
            setPadding(px(10), px(10), px(10), px(10))
            visibility = if (musicOpen) View.VISIBLE else View.GONE
            addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(controls, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) })
        }

        // Botão flutuante ♪ / ✕ (arrastável; a posição fica salva)
        musicFab = Button(this).apply {
            text = if (musicOpen) "✕" else "♪"
            textSize = 22f
            setTextColor(Color.BLACK)
            isAllCaps = false
            stateListAnimator = null
            background = box(context, accent, 28)
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { toggleMusic() }
        }
        musicFab.translationX = Prefs.getInt(this, "fab_x").toFloat()
        musicFab.translationY = Prefs.getInt(this, "fab_y").toFloat()
        musicFab.setOnTouchListener(fabTouch)

        // Botões pequenos: mapa, configurações, 2D/3D e rotação
        val mapBtn = miniBtn("🗺")
        mapBtn.setOnClickListener { setScreen(true) }
        val settingsBtn = miniBtn("⚙")
        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        val mode3dBtn = miniBtn(modeLabel())
        modeBtns.add(mode3dBtn)
        mode3dBtn.setOnClickListener { toggle3D() }
        val rotateBtn = miniBtn(orientLabel())
        rotateBtn.setOnClickListener {
            orientMode = (orientMode + 1) % 3
            applyOrientation()
            rotateBtn.text = orientLabel()
        }
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        navRow.addView(mapBtn, LinearLayout.LayoutParams(px(48), px(44)).apply { setMargins(px(4), 0, px(4), 0) })
        navRow.addView(settingsBtn, LinearLayout.LayoutParams(px(48), px(44)).apply { setMargins(px(4), 0, px(4), 0) })
        navRow.addView(mode3dBtn, LinearLayout.LayoutParams(px(48), px(44)).apply { setMargins(px(4), 0, px(4), 0) })
        navRow.addView(rotateBtn, LinearLayout.LayoutParams(px(48), px(44)).apply { setMargins(px(4), 0, px(4), 0) })

        fun gap(dp: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(dp) }

        if (landscape) {
            // Paisagem: mapa maior à esquerda, dados à direita
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
            left.addView(vehicleView, gap(0))
            left.addView(arcBlock, LinearLayout.LayoutParams(MATCH, 0, 1f))

            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            right.addView(navView, gap(0))
            right.addView(statsRow, gap(4))
            right.addView(tripRow, gap(8))
            right.addView(navRow, gap(10))

            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(right, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            root.addView(left, LinearLayout.LayoutParams(0, MATCH, 1.8f))
            root.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = px(12) })
        } else {
            root.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
            root.addView(vehicleView, gap(0))
            root.addView(arcBlock, LinearLayout.LayoutParams(MATCH, 0, 1.4f))
            root.addView(navView, gap(4))
            root.addView(statsRow, gap(8))
            root.addView(tripRow, gap(8))
            root.addView(navRow, gap(10))
        }
        return root
    }

    private fun buildMapScreen(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF06142B.toInt())
        }

        // Barra de busca, 2D/3D, centralizar (◎) e voltar ao painel
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(8), px(8), px(8))
        }
        searchInput = EditText(this).apply {
            hint = "Para onde? ex: Av. Paulista, 1000"
            setHintTextColor(GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(BTN_BG)
            setPadding(px(12), px(10), px(12), px(10))
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    go()
                    true
                } else false
            }
        }
        val goBtn = Button(this).apply {
            text = "Ir"
            setOnClickListener { go() }
        }
        val mode3dBtn = miniBtn(modeLabel())
        modeBtns.add(mode3dBtn)
        mode3dBtn.setOnClickListener { toggle3D() }
        val centerBtn = miniBtn("◎")
        centerBtn.setOnClickListener { recenter() }
        val backBtn = Button(this).apply {
            text = "Painel"
            setOnClickListener { setScreen(false) }
        }
        topRow.addView(searchInput, LinearLayout.LayoutParams(0, WRAP, 1f))
        topRow.addView(goBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        topRow.addView(mode3dBtn, LinearLayout.LayoutParams(px(44), px(44)).apply { setMargins(px(4), 0, px(4), 0) })
        topRow.addView(centerBtn, LinearLayout.LayoutParams(px(44), px(44)).apply { setMargins(px(4), 0, px(4), 0) })
        topRow.addView(backBtn, LinearLayout.LayoutParams(WRAP, WRAP))

        // Mapa completo
        fullMap = newMap(true)

        // Cartão de instruções
        mapInstructionView = label(22f, Color.WHITE, bold = true).apply { text = "Digite um destino acima" }
        mapInfoView = label(15f, BLUE_LIGHT)
        voiceBtn = Button(this).apply { text = if (voiceOn) "Voz: ligada" else "Voz: desligada" }
        voiceBtn.setOnClickListener {
            voiceOn = !voiceOn
            voiceBtn.text = if (voiceOn) "Voz: ligada" else "Voz: desligada"
            if (!voiceOn) tts?.stop()
        }
        val clearBtn = Button(this).apply {
            text = "Cancelar rota"
            setOnClickListener { clearRoute() }
        }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(voiceBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        btnRow.addView(clearBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        val attrib = label(10f, GRAY).apply { text = "Mapa © OpenStreetMap / OpenFreeMap · Rota OSRM" }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CARD_BG)
            setPadding(px(16), px(12), px(16), px(12))
            addView(mapInstructionView, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(mapInfoView, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(btnRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) })
            addView(attrib, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(4) })
        }

        root.addView(topRow, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(fullMap, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
        return root
    }
}
