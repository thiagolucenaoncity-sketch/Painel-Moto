package com.moto.painel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
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
import android.widget.HorizontalScrollView
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
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------- Constantes ----------

private const val MAP_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val EMPTY_GEO = "{\"type\":\"FeatureCollection\",\"features\":[]}"

// Distância (em metros) para considerar que você saiu da rota
private const val OFF_ROUTE_M = 60.0

// Velocidade abaixo da qual o veículo é considerado parado (km/h) e tempo parado para pausar a trip (ms)
private const val STOP_KMH = 3f
private const val STOP_MS = 4000L

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

// Cor no formato que o mapa entende (#RRGGBB)
private fun hex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

private fun box(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = ctx.pxF(radiusDp.toFloat())
    if (strokeColor != null) setStroke(ctx.px(1), strokeColor)
}

private class Step(val lat: Double, val lon: Double, val text: String, val distanceM: Double)

// Sugestão de endereço (aparece enquanto você digita)
private class Suggest(val name: String, val lat: Double, val lon: Double)

// Endereço favorito, com apelido
private class Fav(val nick: String, val addr: String, val lat: Double, val lon: Double)

// Trip salva pelo usuário
private class SavedTrip(val date: String, val meters: Double, val ms: Long, val maxKmh: Int)

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

// ---------- Arco de velocidade (cor muda por faixa; trilha vazia tem cor própria) ----------

class SpeedArcView(ctx: Context) : View(ctx) {
    var speed: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    // Cores das três faixas e os limites (km/h) entre elas
    private var c1 = BLUE
    private var c2 = YELLOW
    private var c3 = RED
    private var lim1 = 80
    private var lim2 = 120

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

    fun setZones(color1: Int, color2: Int, color3: Int, limit1: Int, limit2: Int, trackColor: Int) {
        c1 = color1
        c2 = color2
        c3 = color3
        lim1 = limit1
        lim2 = limit2
        trackPaint.color = trackColor
        invalidate()
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
            // A cor do arco é a da faixa em que a velocidade atual está
            arcPaint.color = when {
                speed <= lim1 -> c1
                speed <= lim2 -> c2
                else -> c3
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

// ---------- Tela principal: painel + mapa + trips ----------

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null

    // Cores (carregadas das Configurações)
    private var accent = BLUE
    private var cArc1 = BLUE
    private var cArc2 = YELLOW
    private var cArc3 = RED
    private var cArcTrack = 0xFF14284A.toInt()
    private var arcLim1 = 80
    private var arcLim2 = 120
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

    // Tela atual: 0 = painel, 1 = mapa, 2 = trips salvas
    private var screenMode = 0

    // Estilo do mapa: 0 = automático, 1 = claro, 2 = escuro (azul noite)
    private var mapMode = 0
    private val styleBtns = ArrayList<Button>()

    // Mapa 3D (inclinado) ou 2D (visão de cima)
    private var is3D = false
    private val modeBtns = ArrayList<Button>()

    // Seguir a posição: fica falso quando o usuário arrasta o mapa
    private var followMe = true

    // Todos os mapas (miniatura e mapa completo)
    private val holders = ArrayList<MapHolder>()

    private var locationStarted = false
    private var firstFixDone = false

    // Player flutuante: posição absoluta do botão ♪ na tela
    private var musicOpen = false
    private lateinit var musicDock: LinearLayout
    private lateinit var musicFab: Button
    private lateinit var musicPopup: LinearLayout
    private var fabX = -1f
    private var fabY = -1f
    private var downRawX = 0f
    private var downRawY = 0f
    private var startFabX = 0f
    private var startFabY = 0f
    private var fabMoved = false

    // Painel
    private lateinit var panelScreen: LinearLayout
    private lateinit var minimap: MapView
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var batteryView: TextView
    private lateinit var gpsView: TextView
    private lateinit var vehicleView: LinearLayout
    private lateinit var nickView: TextView
    private lateinit var modelView: TextView
    private lateinit var speedView: TextView
    private lateinit var unitView: TextView
    private lateinit var arcView: SpeedArcView
    private lateinit var navView: TextView
    private lateinit var tripHeader: TextView
    private lateinit var distView: TextView
    private lateinit var timeView: TextView
    private lateinit var maxView: TextView
    private lateinit var tripBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var artView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var eqView: EqualizerView
    private lateinit var playBtn: Button

    // Mapa completo
    private lateinit var mapScreen: LinearLayout
    private lateinit var fullMap: MapView
    private lateinit var searchInput: EditText
    private lateinit var suggestScroll: ScrollView
    private lateinit var suggestBox: LinearLayout
    private lateinit var favChips: LinearLayout
    private lateinit var mapInstructionView: TextView
    private lateinit var mapInfoView: TextView
    private lateinit var voiceBtn: Button

    // Trips salvas
    private lateinit var tripsScreen: LinearLayout
    private lateinit var tripsList: LinearLayout

    // Busca: controle das sugestões
    private var skipSuggest = false
    private var suggestSeq = 0
    private var suggestQuery = ""
    private val suggestRunnable = Runnable { fetchSuggestions(suggestQuery) }

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

    // Recálculo de rota
    private var offCount = 0
    private var lastRecalcMs = 0L
    private var recalculating = false

    // Trip (distância em metros, tempo em ms, máxima em km/h)
    private var tripStarted = false
    private var manualPaused = false
    private var autoPaused = false
    private var segStart = 0L
    private var tripElapsed = 0L
    private var stoppedSince = 0L
    private var tripLast: Location? = null
    private var totalM = 0.0
    private var maxSpeed = 0

    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEE, dd MMM", Locale("pt", "BR"))
    private val saveFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clockView.text = clockFmt.format(now)
            dateView.text = dateFmt.format(now)
            updateBattery()
            updateTime()
            updateTripButtons()
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
            setGps("● GPS ok (±${loc.accuracy.roundToInt()} m)", GREEN)

            // Pausa automática: parado por um tempo pausa a trip; voltando a andar, retoma
            if (tripStarted && !manualPaused) {
                val now = System.currentTimeMillis()
                if (kmh < STOP_KMH) {
                    if (stoppedSince == 0L) stoppedSince = now
                    if (!autoPaused && now - stoppedSince > STOP_MS) {
                        autoPaused = true
                        syncRunning()
                    }
                } else {
                    stoppedSince = 0L
                    if (autoPaused) {
                        autoPaused = false
                        syncRunning()
                    }
                }
            }

            // Máxima só conta enquanto a trip está correndo
            if (isRunning() && shown > maxSpeed) maxSpeed = shown
            maxView.text = speedText(maxSpeed)

            // Distância só soma enquanto a trip está correndo
            tripLast?.let { prev ->
                if (isRunning()) {
                    val d = prev.distanceTo(loc)
                    if (d in 3f..500f) totalM += d
                }
            }
            distView.text = distText(totalM)
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

    // Toque no botão ♪: arrastar move o botão (e o player junto); um toque simples abre ou fecha o player
    private val fabTouch = View.OnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                startFabX = fabX
                startFabY = fabY
                fabMoved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.rawX - downRawX) > 12 || abs(e.rawY - downRawY) > 12) fabMoved = true
                if (fabMoved) {
                    fabX = startFabX + (e.rawX - downRawX)
                    fabY = startFabY + (e.rawY - downRawY)
                    applyFabLayout()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (fabMoved) {
                    Prefs.putRaw(this@MainActivity, "dock_x", fabX.roundToInt())
                    Prefs.putRaw(this@MainActivity, "dock_y", fabY.roundToInt())
                    Prefs.putRaw(this@MainActivity, "dock_set", 1)
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
                m.setStyle(Style.Builder().fromUri(MAP_URL)) { s -> onStyleReady(this, s) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tela cheia: esconde a barra de status do Android (a bateria aparece dentro do app)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MapLibre.getInstance(this)

        // Posição salva do botão ♪ (se o usuário já arrastou)
        if (Prefs.getInt(this, "dock_set") == 1) {
            fabX = Prefs.getInt(this, "dock_x").toFloat()
            fabY = Prefs.getInt(this, "dock_y").toFloat()
        }

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

    // ---------- Bateria ----------

    private fun updateBattery() {
        if (!::batteryView.isInitialized) return
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val icon = if (bm.isCharging) "⚡" else "🔋"
        batteryView.text = "$icon $pct%"
    }

    // ---------- Trip (início, pausa, finalização e salvamento) ----------

    private fun isRunning(): Boolean = tripStarted && !manualPaused && !autoPaused

    // Liga ou desliga o cronômetro conforme o estado da trip
    private fun syncRunning() {
        val now = System.currentTimeMillis()
        val r = isRunning()
        if (r && segStart == 0L) segStart = now
        if (!r && segStart != 0L) {
            tripElapsed += now - segStart
            segStart = 0L
        }
    }

    private fun elapsedMs(): Long {
        val now = System.currentTimeMillis()
        return tripElapsed + (if (segStart != 0L) now - segStart else 0L)
    }

    private fun startTrip() {
        tripStarted = true
        manualPaused = false
        autoPaused = false
        stoppedSince = 0L
        syncRunning()
        updateTripButtons()
    }

    private fun finishTrip() {
        tripStarted = false
        manualPaused = false
        autoPaused = false
        syncRunning()
        updateTripButtons()
    }

    // Pausa ou retoma manualmente (o botão pequeno)
    private fun toggleManualPause() {
        if (!tripStarted) return
        manualPaused = !manualPaused
        autoPaused = false
        syncRunning()
        updateTripButtons()
    }

    private fun resetTrip() {
        tripStarted = false
        manualPaused = false
        autoPaused = false
        stoppedSince = 0L
        tripElapsed = 0L
        segStart = 0L
        totalM = 0.0
        maxSpeed = 0
        tripLast = null
        distView.text = distText(0.0)
        maxView.text = speedText(0)
        timeView.text = "00:00:00"
        updateTripButtons()
    }

    // Atualiza os textos dos botões e o status da trip
    private fun updateTripButtons() {
        if (!::tripBtn.isInitialized) return
        tripBtn.text = if (tripStarted) "■ Finalizar" else "▶ Iniciar"
        pauseBtn.visibility = if (tripStarted) View.VISIBLE else View.GONE
        pauseBtn.text = if (isRunning()) "⏸" else "▶"
        tripHeader.text = when {
            !tripStarted -> "TRIP · PARADA"
            manualPaused -> "TRIP · ⏸ PAUSADA"
            autoPaused -> "TRIP · ⏸ PAUSA AUTOMÁTICA"
            else -> "TRIP · ● EM ANDAMENTO"
        }
    }

    private fun updateTime() {
        if (!::timeView.isInitialized) return
        timeView.text = fmtHMS(elapsedMs())
    }

    private fun fmtHMS(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    // Salva a trip atual. Guarda no máximo 3: a mais antiga sai quando a quarta é salva.
    private fun saveTrip() {
        val ms = elapsedMs()
        if (totalM < 1.0 && ms < 1000L) {
            toast("Nada para salvar ainda")
            return
        }
        val list = loadTrips()
        var removed = false
        while (list.size >= 3) {
            list.removeAt(0)
            removed = true
        }
        list.add(SavedTrip(saveFmt.format(Date()), totalM, ms, maxSpeed))
        saveTrips(list)
        toast(if (removed) "Trip salva. A mais antiga foi removida (máximo de 3)" else "Trip salva")
    }

    private fun tripPrefs() = getSharedPreferences("trips", MODE_PRIVATE)

    private fun loadTrips(): MutableList<SavedTrip> {
        val out = ArrayList<SavedTrip>()
        try {
            val arr = JSONArray(tripPrefs().getString("list", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(SavedTrip(o.getString("date"), o.getDouble("m"), o.getLong("ms"), o.getInt("max")))
            }
        } catch (e: Exception) {
            // Se os dados estiverem corrompidos, começa a lista vazia
        }
        return out
    }

    private fun saveTrips(list: List<SavedTrip>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("date", it.date)
                    .put("m", it.meters)
                    .put("ms", it.ms)
                    .put("max", it.maxKmh)
            )
        }
        tripPrefs().edit().putString("list", arr.toString()).apply()
    }

    // Redesenha a lista de trips salvas (mais recente primeiro)
    private fun refreshTripsList() {
        if (!::tripsList.isInitialized) return
        tripsList.removeAllViews()
        val list = loadTrips()
        if (list.isEmpty()) {
            tripsList.addView(
                label(15f, cLabel, align = Gravity.CENTER).apply { text = "Nenhuma trip salva ainda." },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(16) }
            )
            return
        }
        for (i in list.indices.reversed()) {
            val t = list[i]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = box(context, cCard, 14, cBorder)
                setPadding(px(14), px(12), px(14), px(12))
            }
            card.addView(label(16f, Color.WHITE, bold = true).apply { text = t.date })
            card.addView(
                label(14f, cLabel).apply {
                    text = "Distância: ${distText(t.meters)}\n" +
                        "Tempo: ${fmtHMS(t.ms)}\n" +
                        "Máxima: ${speedText(t.maxKmh)} ${unitSpeed()}"
                },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(6) }
            )
            val idx = i
            val delBtn = pill("Excluir", 0xFF8B1E2D.toInt(), Color.WHITE, 14f).apply {
                setOnClickListener {
                    val current = loadTrips()
                    if (idx < current.size) current.removeAt(idx)
                    saveTrips(current)
                    refreshTripsList()
                    toast("Trip excluída")
                }
            }
            card.addView(delBtn, LinearLayout.LayoutParams(MATCH, px(40)).apply { topMargin = px(8) })
            tripsList.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(10) })
        }
    }

    // ---------- Cores ----------

    // Lê todas as cores escolhidas nas Configurações
    private fun loadColors() {
        accent = Prefs.accentColor(this)
        cLabel = Prefs.colorOf(this, "label_c", GRAY)
        cClock = Prefs.colorOf(this, "clock_c", BLUE_LIGHT)
        cStat = Prefs.colorOf(this, "stat_c", Color.WHITE)
        cArc1 = Prefs.arcZoneColor(this, 1, accent)
        cArc2 = Prefs.arcZoneColor(this, 2, accent)
        cArc3 = Prefs.arcZoneColor(this, 3, accent)
        cArcTrack = Prefs.colorOf(this, "arc_track", 0xFF14284A.toInt())
        arcLim1 = Prefs.intOr(this, "arc_lim1", 80)
        arcLim2 = Prefs.intOr(this, "arc_lim2", 120)
        cNum = Prefs.colorOf(this, "num_color", Color.WHITE)
        cUnit = Prefs.colorOf(this, "unit_color", cLabel)
        cCard = withAlpha(Prefs.colorOf(this, "card_c", 0xFF0C1B33.toInt()), 0xCC)
        cBorder = withAlpha(accent, 0x80)
        cGrid = withAlpha(Prefs.colorOf(this, "grid_c", BLUE), 0x1E)
        cBgCustom = Prefs.colorOf(this, "bg_c", 0)
        units = Prefs.getInt(this, "units")
        appliedVer = Prefs.version(this)
        mapMode = Prefs.getInt(this, "map_style")
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

    // ---------- Player flutuante (o botão decide para onde o player abre) ----------

    // Posiciona o botão ♪ e o player conforme o lado da tela em que o botão está
    private fun applyFabLayout() {
        val parent = musicDock.parent as? View ?: return
        val pw = parent.width
        val ph = parent.height
        if (pw == 0 || ph == 0) return

        val s = px(56)
        val gap = px(8)
        val popupW = px(280)

        // Primeira vez: canto inferior direito
        if (fabX < 0f) {
            fabX = (pw - s - px(16)).toFloat()
            fabY = (ph - s - px(16)).toFloat()
        }
        fabX = fabX.coerceIn(0f, (pw - s).toFloat())
        fabY = fabY.coerceIn(0f, (ph - s).toFloat())

        // Lado: direita ou esquerda; metade: embaixo ou em cima
        val onRight = fabX + s / 2f > pw / 2f
        val onBottom = fabY + s / 2f > ph / 2f
        val align = if (onRight) Gravity.END else Gravity.START

        // Ordem dos filhos: se o botão está embaixo, o player vai acima dele; senão, abaixo
        val popupLp = LinearLayout.LayoutParams(popupW, WRAP).apply {
            gravity = align
            if (onBottom) bottomMargin = gap else topMargin = gap
        }
        val fabLp = LinearLayout.LayoutParams(s, s).apply { gravity = align }
        val first: View = if (onBottom) musicPopup else musicFab
        val second: View = if (onBottom) musicFab else musicPopup
        if (musicDock.childCount != 2 || musicDock.getChildAt(0) != first) {
            musicDock.removeAllViews()
            musicDock.addView(first, if (onBottom) popupLp else fabLp)
            musicDock.addView(second, if (onBottom) fabLp else popupLp)
        } else {
            musicDock.updateViewLayout(first, if (onBottom) popupLp else fabLp)
            musicDock.updateViewLayout(second, if (onBottom) fabLp else popupLp)
        }
        musicDock.gravity = align

        // Tamanho atual do bloco (player aberto ou só o botão)
        val popupH = if (musicPopup.height > 0) musicPopup.height else px(200)
        val dw = if (musicOpen) popupW else s
        val dh = if (musicOpen) popupH + gap + s else s

        var dx = if (onRight) fabX + s - dw else fabX
        var dy = if (onBottom) fabY + s - dh else fabY
        dx = dx.coerceIn(0f, maxOf(0f, (pw - dw).toFloat()))
        dy = dy.coerceIn(0f, maxOf(0f, (ph - dh).toFloat()))
        musicDock.translationX = dx
        musicDock.translationY = dy
    }

    private fun toggleMusic() {
        musicOpen = !musicOpen
        musicPopup.visibility = if (musicOpen) View.VISIBLE else View.GONE
        musicFab.text = if (musicOpen) "✕" else "♪"
        musicDock.post { applyFabLayout() }
    }

    // ---------- Estilo do mapa (claro, azul noite ou automático) ----------

    // Modo noite: o usuário escolheu escuro, ou está automático com o celular no modo escuro
    private fun isNightNow(): Boolean = when (Prefs.getInt(this, "map_style")) {
        1 -> false
        2 -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun styleLabel(): String = when (mapMode) {
        1 -> "☀"
        2 -> "☾"
        else -> "A"
    }

    // Troca o estilo de todos os mapas e refaz as camadas (rota, posição, prédios)
    private fun applyMapStyle() {
        holders.forEach { h ->
            h.map?.setStyle(Style.Builder().fromUri(MAP_URL)) { s -> onStyleReady(h, s) }
        }
    }

    // Modo azul noite: fundo azul escuro, água mais escura, vias bem claras e nomes legíveis
    private fun applyNightTheme(s: Style) {
        for (layer in s.layers) {
            val id = layer.id.lowercase(Locale.ROOT)
            try {
                when (layer) {
                    is BackgroundLayer -> layer.setProperties(
                        PropertyFactory.backgroundColor(hex(0xFF0B1A2E.toInt()))
                    )
                    is FillLayer -> {
                        val c = when {
                            id.contains("water") -> 0xFF0E2F55.toInt()
                            id.contains("park") || id.contains("wood") ||
                                id.contains("grass") || id.contains("landuse") -> 0xFF10283F.toInt()
                            else -> 0xFF13243D.toInt()
                        }
                        layer.setProperties(PropertyFactory.fillColor(hex(c)))
                    }
                    is LineLayer -> {
                        val isRoad = listOf(
                            "road", "highway", "transport", "bridge", "tunnel", "motorway",
                            "primary", "secondary", "tertiary", "trunk", "minor", "street"
                        ).any { id.contains(it) }
                        val c = if (isRoad) 0xFF7CC4FF.toInt() else 0xFF2A4A70.toInt()
                        layer.setProperties(PropertyFactory.lineColor(hex(c)))
                    }
                    is SymbolLayer -> layer.setProperties(
                        PropertyFactory.textColor(hex(0xFFD6E6FF.toInt())),
                        PropertyFactory.textHaloColor(hex(0xFF0B1A2E.toInt()))
                    )
                }
            } catch (e: Exception) {
                // Se alguma camada não aceitar a cor, ela continua com a cor original
            }
        }
    }

    // Prédios aparecem só se o 3D estiver ativo e a opção "Prédios 3D" estiver ligada nas Configurações
    private fun buildingsOn(): Boolean =
        is3D && Prefs.getInt(this, "buildings") == 1

    // ---------- Mapa: criação, estilo e camadas ----------

    private fun newMap(interactive: Boolean): MapView {
        val h = MapHolder(interactive)
        holders.add(h)
        return h.view
    }

    private fun onStyleReady(h: MapHolder, s: Style) {
        h.style = s
        if (isNightNow()) applyNightTheme(s)
        s.addImage("me-icon", styledMarker(Prefs.getInt(this, "marker"), accent))

        val rs = GeoJsonSource("route-src")
        val ds = GeoJsonSource("dest-src")
        val ms = GeoJsonSource("me-src")
        h.routeSrc = rs
        h.destSrc = ds
        h.meSrc = ms
        s.addSource(rs)
        s.addSource(ds)
        s.addSource(ms)

        // Prédios em 3D: usa a fonte de dados vetoriais do estilo atual
        val vec = s.sources.firstOrNull { it is VectorSource } as? VectorSource
        if (vec != null) {
            try {
                val isProto = vec.id.contains("proto", ignoreCase = true)
                val b = FillExtrusionLayer("building-3d", vec.id)
                    .withSourceLayer(if (isProto) "buildings" else "building")
                    .withProperties(
                        PropertyFactory.fillExtrusionHeight(
                            Expression.get(if (isProto) "height" else "render_height")
                        ),
                        PropertyFactory.fillExtrusionBase(
                            Expression.get(if (isProto) "min_height" else "render_min_height")
                        ),
                        PropertyFactory.fillExtrusionColor(0xFF2A3F63.toInt()),
                        PropertyFactory.fillExtrusionOpacity(0.9f),
                        PropertyFactory.visibility(if (buildingsOn()) Property.VISIBLE else Property.NONE)
                    )
                s.addLayer(b)
                h.buildings = b
            } catch (e: Exception) {
                // Se o estilo não tiver a camada de prédios, o mapa continua em 2D
            }
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

    // Alterna entre visão 2D (de cima) e 3D (inclinada)
    private fun toggle3D() {
        is3D = !is3D
        holders.forEach { h ->
            h.buildings?.setProperties(PropertyFactory.visibility(if (buildingsOn()) Property.VISIBLE else Property.NONE))
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

    // ---------- Busca e sugestões ----------

    private val searchWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (skipSuggest) {
                skipSuggest = false
                return
            }
            scheduleSuggest(s?.toString() ?: "")
        }
    }

    // Espera um pouco depois de cada letra antes de buscar (para não sobrecarregar o servidor)
    private fun scheduleSuggest(q: String) {
        suggestQuery = q.trim()
        handler.removeCallbacks(suggestRunnable)
        if (suggestQuery.length < 3) {
            hideSuggestions()
            return
        }
        handler.postDelayed(suggestRunnable, 450)
    }

    private fun fetchSuggestions(q: String) {
        val seq = ++suggestSeq
        Thread {
            try {
                val arr = JSONArray(
                    getText(
                        "https://nominatim.openstreetmap.org/search?format=json&limit=5&q=" +
                            URLEncoder.encode(q, "UTF-8")
                    )
                )
                val list = ArrayList<Suggest>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(Suggest(o.optString("display_name"), o.getDouble("lat"), o.getDouble("lon")))
                }
                handler.post { if (seq == suggestSeq) showSuggestions(list) }
            } catch (e: Exception) {
                handler.post { if (seq == suggestSeq) hideSuggestions() }
            }
        }.start()
    }

    private fun showSuggestions(list: List<Suggest>) {
        if (!::suggestBox.isInitialized) return
        suggestBox.removeAllViews()
        if (list.isEmpty()) {
            hideSuggestions()
            return
        }
        for (sg in list) {
            suggestBox.addView(
                TextView(this).apply {
                    text = sg.name
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    setPadding(px(12), px(10), px(12), px(10))
                    setOnClickListener {
                        skipSuggest = true
                        searchInput.setText(sg.name)
                        searchInput.setSelection(sg.name.length)
                        hideSuggestions()
                        routeTo(sg.lat, sg.lon, sg.name)
                    }
                },
                LinearLayout.LayoutParams(MATCH, WRAP)
            )
        }
        suggestScroll.visibility = View.VISIBLE
    }

    private fun hideSuggestions() {
        if (!::suggestBox.isInitialized) return
        suggestBox.removeAllViews()
        suggestScroll.visibility = View.GONE
    }

    // Pesquisa o texto digitado e traça a rota para o primeiro resultado
    private fun go() {
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) return
        hideSuggestions()
        if (lastFix == null) {
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
                val name = hit.optString("display_name", q)
                handler.post { routeTo(destLat, destLon, name) }
            } catch (e: Exception) {
                handler.post { toast("Não consegui calcular a rota. Verifique a internet.") }
            }
        }.start()
    }

    // Traça a rota da sua posição até um ponto (usado pela busca, sugestões e favoritos)
    private fun routeTo(lat: Double, lon: Double, name: String) {
        val origin = lastFix
        if (origin == null) {
            toast("Aguardando sinal do GPS...")
            return
        }
        toast("Buscando rota...")
        fetchRoute(origin, lat, lon, name.take(60), false)
    }

    // ---------- Favoritos (endereço + apelido) ----------

    private fun favPrefs() = getSharedPreferences("favoritos", MODE_PRIVATE)

    private fun loadFavs(): MutableList<Fav> {
        val out = ArrayList<Fav>()
        try {
            val arr = JSONArray(favPrefs().getString("list", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Fav(o.getString("nick"), o.getString("addr"), o.getDouble("lat"), o.getDouble("lon")))
            }
        } catch (e: Exception) {
            // Se os dados estiverem corrompidos, começa a lista vazia
        }
        return out
    }

    private fun saveFavs(list: List<Fav>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("nick", it.nick)
                    .put("addr", it.addr)
                    .put("lat", it.lat)
                    .put("lon", it.lon)
            )
        }
        favPrefs().edit().putString("list", arr.toString()).apply()
    }

    // Recria os botões de favoritos na barra do mapa
    private fun refreshFavChips() {
        if (!::favChips.isInitialized) return
        favChips.removeAllViews()
        loadFavs().forEach { f ->
            favChips.addView(
                Button(this).apply {
                    text = "★ ${f.nick}"
                    textSize = 13f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    background = box(context, BTN_BG, 16, BORDER)
                    minHeight = 0
                    minimumHeight = 0
                    setOnClickListener {
                        skipSuggest = true
                        searchInput.setText(f.addr)
                        hideSuggestions()
                        routeTo(f.lat, f.lon, f.addr)
                    }
                    setOnLongClickListener {
                        confirmRemoveFav(f)
                        true
                    }
                },
                LinearLayout.LayoutParams(WRAP, px(40)).apply { setMargins(px(4), 0, px(4), 0) }
            )
        }
    }

    // Salva o destino atual como favorito, com um apelido escolhido por você
    private fun askFavNick() {
        val dest = routeDest
        if (dest == null) {
            toast("Escolha um destino primeiro")
            return
        }
        val input = EditText(this).apply {
            hint = "Ex: Casa, Trabalho"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Salvar favorito")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val nick = input.text.toString().trim()
                if (nick.isEmpty()) {
                    toast("Digite um apelido")
                    return@setPositiveButton
                }
                val list = loadFavs()
                list.add(Fav(nick, routeName, dest.latitude, dest.longitude))
                saveFavs(list)
                refreshFavChips()
                toast("Favorito salvo")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmRemoveFav(f: Fav) {
        AlertDialog.Builder(this)
            .setTitle("Remover ${f.nick}?")
            .setPositiveButton("Remover") { _, _ ->
                saveFavs(loadFavs().filterNot { it.nick == f.nick && it.addr == f.addr })
                refreshFavChips()
            }
            .setNegativeButton("Cancelar", null)
            .show()
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

    // ---------- Telas (0 = painel, 1 = mapa, 2 = trips salvas) ----------

    private fun setScreen(mode: Int) {
        screenMode = mode
        panelScreen.visibility = if (mode == 0) View.VISIBLE else View.GONE
        mapScreen.visibility = if (mode == 1) View.VISIBLE else View.GONE
        tripsScreen.visibility = if (mode == 2) View.VISIBLE else View.GONE
        musicDock.visibility = if (mode == 0) View.VISIBLE else View.GONE
        if (mode == 2) refreshTripsList()
    }

    // ---------- Navegação ----------

    private fun onFix(loc: Location) {
        if (steps.isEmpty() || stepIndex >= steps.size) return

        // Verifica se saiu da rota; se ficou fora por alguns segundos, recalcula
        if (routePoints.size > 1) {
            val off = distToRouteM(loc.latitude, loc.longitude)
            if (off > OFF_ROUTE_M) {
                offCount++
                if (offCount >= 3 && !recalculating && System.currentTimeMillis() - lastRecalcMs > 20000) {
                    recalculate(loc)
                    return
                }
            } else {
                offCount = 0
            }
        }

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

    // Menor distância (em metros) entre sua posição e a linha da rota
    private fun distToRouteM(lat: Double, lon: Double): Double {
        val pts = routePoints
        if (pts.size < 2) return 0.0
        val cosLat = cos(Math.toRadians(lat))
        var best = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val ax = (a.longitude - lon) * cosLat * 111320.0
            val ay = (a.latitude - lat) * 110540.0
            val bx = (b.longitude - lon) * cosLat * 111320.0
            val by = (b.latitude - lat) * 110540.0
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + dx * t
            val py = ay + dy * t
            val d = sqrt(px * px + py * py)
            if (d < best) best = d
        }
        return best
    }

    // Pede uma nova rota a partir da posição atual, avisando por voz
    private fun recalculate(loc: Location) {
        val dest = routeDest ?: return
        recalculating = true
        lastRecalcMs = System.currentTimeMillis()
        offCount = 0
        say("Recalculando rota")
        fetchRoute(loc, dest.latitude, dest.longitude, routeName, true)
    }

    // Mostra a instrução no cartão do mapa e na linha de navegação do painel
    private fun showInstruction(main: String, sub: String) {
        mapInstructionView.text = main
        mapInfoView.text = sub
        navView.text = "➜ $main"
        navView.visibility = if (main.isEmpty()) View.GONE else View.VISIBLE
        navView.isSelected = true
    }

    // Busca a rota (usada na primeira vez e também no recálculo)
    private fun fetchRoute(origin: Location, destLat: Double, destLon: Double, name: String, isRecalc: Boolean) {
        Thread {
            try {
                val url = "https://router.project-osrm.org/route/v1/driving/" +
                    "${origin.longitude},${origin.latitude};$destLon,$destLat" +
                    "?overview=full&geometries=geojson&steps=true"
                val res = JSONObject(getText(url))
                if (res.optString("code") != "Ok") {
                    handler.post { if (!isRecalc) toast("Não achei rota até lá") }
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
                    applyRoute(points, list, LatLng(destLat, destLon), name, total, dur, isRecalc)
                }
            } catch (e: Exception) {
                handler.post { if (!isRecalc) toast("Não consegui calcular a rota. Verifique a internet.") }
            } finally {
                recalculating = false
            }
        }.start()
    }

    private fun applyRoute(
        points: List<LatLng>,
        list: List<Step>,
        dest: LatLng,
        name: String,
        totalM: Double,
        durS: Double,
        isRecalc: Boolean
    ) {
        routePoints = points
        routeDest = dest
        routeName = name
        steps = list
        announcedNear = false
        offCount = 0
        lastRecalcMs = System.currentTimeMillis()
        // O primeiro passo começa no seu ponto atual, então pulamos ele
        stepIndex = if (list.size > 1) 1 else 0

        holders.forEach { refreshRoute(it) }

        // Na primeira rota, mostra o trajeto inteiro no mapa grande (para de seguir até tocar em ◎)
        if (!isRecalc) {
            followMe = false
            val full = holders.firstOrNull { it.interactive }?.map
            if (full != null && points.size > 1) {
                val bounds = LatLngBounds.Builder().includes(points).build()
                full.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
            }
        }

        if (list.isNotEmpty()) {
            val first = list[stepIndex]
            showInstruction(first.text, "Total ${fmtDist(totalM)} · cerca de ${(durS / 60).roundToInt()} min")
            say(if (isRecalc) "Rota recalculada. ${first.text}" else first.text)
        }
    }

    private fun clearRoute() {
        routePoints = emptyList()
        routeDest = null
        steps = emptyList()
        stepIndex = 0
        offCount = 0
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT
