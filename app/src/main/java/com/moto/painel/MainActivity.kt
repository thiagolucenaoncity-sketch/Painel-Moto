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
import android.util.TypedValue
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

// ---------- Cores padrão e helpers ----------

private val BLUE = 0xFF2EA8FF.toInt()
private val BLUE_LIGHT = 0xFF8FD8FF.toInt()
private val GREEN = 0xFF3DDC84.toInt()
private val YELLOW = 0xFFFFC107.toInt()
private val RED = 0xFFFF4D4D.toInt()
private val GRAY = 0xFF7F93B5.toInt()
private val BORDER = 0xFF1F4E8C.toInt()
private val BTN_BG = 0xFF14284A.toInt()

private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private fun Context.px(v: Int): Int = (v * resources.displayMetrics.density).toInt()
private fun Context.pxF(v: Float): Float = v * resources.displayMetrics.density

// Troca só a transparência de uma cor
private fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

// Escurece uma cor pelo fator f (0 = preto, 1 = igual)
private fun shade(c: Int, f: Float): Int = Color.rgb(
    (Color.red(c) * f).roundToInt(),
    (Color.green(c) * f).roundToInt(),
    (Color.blue(c) * f).roundToInt()
)

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

class GridView(ctx: Context, lineColor: Int) : View(ctx) {
    private val paint = Paint().apply { color = lineColor; strokeWidth = 1f }

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

    var arcColor: Int = BLUE

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
                else -> arcColor
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

    // Cores (carregadas das Configurações)
    private var accent = BLUE
    private var cArc = BLUE
    private var cNum = Color.WHITE
    private var cUnit = GRAY
    private var cLabel = GRAY
    private var cClock = BLUE_LIGHT
    private var cStat = Color.WHITE
    private var cCard = 0xCC0C1B33.toInt()
    private var cBorder = BORDER
    private var cGrid = 0x1E2EA8FF
    private var cBgCustom = 0

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

    // Player flutuante: o botão e o painel formam um bloco que se arrasta junto
    private var musicOpen = false
    private lateinit var musicDock: LinearLayout
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

    // Toque no botão ♪: arrastar move o bloco inteiro; um toque simples abre ou fecha o player
    private val fabTouch = View.OnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fabDX = musicDock.translationX - e.rawX
                fabDY = musicDock.translationY - e.rawY
                fabDownX = e.rawX
                fabDownY = e.rawY
                fabMoved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.rawX - fabDownX) > 12 || abs(e.rawY - fabDownY) > 12) fabMoved = true
                if (fabMoved) applyDockPos(e.rawX + fabDX, e.rawY + fabDY)
                true
            }
            MotionEvent.ACTION_UP -> {
                if (fabMoved) {
                    Prefs.putRaw(this@MainActivity, "fab_x", musicDock.translationX.roundToInt())
                    Prefs.putRaw(this@MainActivity, "fab_y", musicDock.translationY.roundToInt())
                } else {
                    musicFab.performClick()
                }
                true
            }
            else -> true
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
        setGps("● GPS: aguardando sinal", cLabel)
    }

    private fun setGps(text: String, color: Int) {
        gpsView.text = text
        gpsView.setTextColor(color)
    }

    // ---------- Cores ----------

    // Lê todas as cores escolhidas nas Configurações
    private fun loadColors() {
        accent = Prefs.accentColor(this)
        cLabel = Prefs.colorOf(this, "label_c", GRAY)
        cClock = Prefs.colorOf(this, "clock_c", BLUE_LIGHT)
        cStat = Prefs.colorOf(this, "stat_c", Color.WHITE)
        cArc = Prefs.colorOf(this, "arc_color", accent)
        cNum = Prefs.colorOf(this, "num_color", Color.WHITE)
        cUnit = Prefs.colorOf(this, "unit_color", cLabel)
        cCard = withAlpha(Prefs.colorOf(this, "card_c", 0xFF0C1B33.toInt()), 0xCC)
        cBorder = withAlpha(accent, 0x80)
        cGrid = withAlpha(Prefs.colorOf(this, "grid_c", BLUE), 0x1E)
        cBgCustom = Prefs.colorOf(this, "bg_c", 0)
        units = Prefs.getInt(this, "units")
        appliedVer = Prefs.version(this)
    }

    // Fundo: cor personalizada se o usuário escolheu, senão o plano de fundo escolhido
    private fun bgDrawable(style: Int): GradientDrawable {
        val pair: Pair<Int, Int> = if (cBgCustom != 0) {
            Pair(cBgCustom, shade(cBgCustom, 0.35f))
        } else when (style) {
            1 -> Pair(0xFF000000.toInt(), 0xFF000000.toInt())
            2 -> Pair(0xFF0B3A6E.toInt(), 0xFF02060F.toInt())
            3 -> Pair(0xFF3A1B6E.toInt(), 0xFF06040F.toInt())
            else -> Pair(0xFF06142B.toInt(), 0xFF02060F.toInt())
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pair.first, pair.second))
    }

    // ---------- Player flutuante (bloco arrastável) ----------

    // Mantém o bloco dentro da tela
    private fun clampDock(x: Float, y: Float): Pair<Float, Float> {
        val parent = musicDock.parent as? View ?: return Pair(x, y)
        val m = px(16).toFloat()
        val minX = -(parent.width - m - musicDock.width)
        val minY = -(parent.height - m - musicDock.height)
        val cx = x.coerceIn(minOf(minX, m), m)
        val cy = y.coerceIn(minOf(minY, m), m)
        return Pair(cx, cy)
    }

    private fun applyDockPos(x: Float, y: Float) {
        val (cx, cy) = clampDock(x, y)
        musicDock.translationX = cx
        musicDock.translationY = cy
    }

    private fun toggleMusic() {
        musicOpen = !musicOpen
        musicPopup.visibility = if (musicOpen) View.VISIBLE else View.GONE
        musicFab.text = if (musicOpen) "✕" else "♪"
        // Ao abrir, o painel cresce para cima: reajusta para não sair da tela
        musicDock.post { applyDockPos(musicDock.translationX, musicDock.translationY) }
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
