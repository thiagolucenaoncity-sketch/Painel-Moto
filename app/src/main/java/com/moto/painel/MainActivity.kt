package com.moto.painel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import org.maplibre.android.style.layers.TransitionOptions
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
import kotlin.math.ceil
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

// ---------- Arco de velocidade (cor muda por faixa e o fundo vazio tem cor própria) ----------

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
    private var style = 0
    private var scaleMax = 200f

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

    fun configure(styleId: Int, thicknessDp: Int, maxKmh: Int) {
        style = styleId
        scaleMax = maxKmh.coerceIn(120, 280).toFloat()
        val stroke = context.pxF(thicknessDp.coerceIn(8, 24).toFloat())
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        invalidate()
    }

    fun setZones(color1: Int, color2: Int, color3: Int, limit1: Int, limit2: Int, track: Int) {
        c1 = color1
        c2 = color2
        c3 = color3
        lim1 = limit1
        lim2 = limit2
        trackPaint.color = track
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val frac = (speed / scaleMax).coerceIn(0f, 1f)
        arcPaint.color = when {
            speed <= lim1 -> c1
            speed <= lim2 -> c2
            else -> c3
        }

        if (style == 4) {
            // Barra esportiva no rodapé do velocímetro, sem cobrir os números.
            val left = context.pxF(28f)
            val right = width - left
            if (right <= left) return
            val y = height * 0.80f
            canvas.drawLine(left, y, right, y, trackPaint)
            if (frac > 0f) canvas.drawLine(left, y, left + (right - left) * frac, y, arcPaint)
            return
        }

        val pad = context.pxF(20f)
        val side = (minOf(width, height).toFloat() - pad * 2f).coerceAtLeast(1f)
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        rect.set(left, top, left + side, top + side)

        val start = when (style) {
            2 -> -90f // Anel 360°
            3 -> 180f // Semicírculo superior
            else -> 135f // Arco original
        }
        val sweep = when (style) {
            2 -> 360f
            3 -> 180f
            else -> 270f
        }
        canvas.drawArc(rect, start, sweep, false, trackPaint)
        if (frac > 0f) canvas.drawArc(rect, start, sweep * frac, false, arcPaint)
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
    private var isConnected: Boolean? = null
    private var offlineNotice: TextView? = null
    private var lastRoadDataCheckMs = 0L
    private fun online(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
    private fun updateConnection() {
        val now = online()
        val previousState = isConnected
        val currentTime = System.currentTimeMillis()
        // Somente a cada 15 dias a base de SP vai à internet; a verificação
        // deste prazo é barata e roda a cada 15 min, ou ao reconectar.
        if (now && (previousState != true || currentTime - lastRoadDataCheckMs > 15L * 60L * 1000L)) {
            lastRoadDataCheckMs = currentTime
            RadarDatabaseUpdater.checkWhenDue(this, true) { roadAlerts.databaseUpdated() }
        }
        if (now == isConnected) return
        val previously = isConnected
        isConnected = now
        offlineNotice?.apply {
            visibility = if (now) View.GONE else View.VISIBLE
            text = "SEM INTERNET · mapa salvo / cache"
        }
        if (!now) {
            if (previously == true) toast("Sem internet: GPS e mapas salvos continuam disponíveis")
        } else if (previously == false) {
            toast("Conexão restabelecida. Mapa online novamente.")
            // Recarrega somente estilos que nao haviam sido carregados.
            holders.forEach { h ->
                if (h.style == null) h.map?.setStyle(Style.Builder().fromUri(MAP_URL)) { style -> onStyleReady(h, style) }
            }
            lastFix?.let { roadAlerts.fetchAt(it.latitude, it.longitude) }
        }
    }
    private var controller: MediaController? = null

    // Cores (carregadas das Configurações)
    private var accent = BLUE
    private var cArc1 = BLUE
    private var cArc2 = YELLOW
    private var cArc3 = RED
    private var cTrack = 0xFF14284A.toInt()
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

    // O RAVO inicia em perspectiva 3D (inclinação de câmera).
    // O botão 2D/3D alterna a visualização sem mudar a configuração dos prédios.
    private var is3D = true
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
    private lateinit var panelEtaCard: RouteEtaCard
    private lateinit var minimap: MapView
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var batteryView: BatteryStatusView
    private lateinit var gpsView: TextView
    private lateinit var vehicleView: LinearLayout
    private lateinit var nickView: TextView
    private lateinit var modelView: TextView
    private lateinit var speedView: TextView
    private lateinit var unitView: TextView
    private lateinit var arcView: SpeedArcView
    private lateinit var miniRoadLimitPlate: LinearLayout
    private lateinit var miniRoadLimitIcon: ImageView
    private lateinit var fullRoadLimitPlate: LinearLayout
    private lateinit var fullRoadLimitIcon: ImageView
    private var currentRoadLimit: Int? = null
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

    // Mapa completo (tela cheia, com controles flutuantes por cima)
    private lateinit var mapScreen: FrameLayout
    private lateinit var fullEtaCard: RouteEtaCard
    private lateinit var fullMap: MapView
    private lateinit var searchInput: EditText
    private lateinit var suggestScroll: ScrollView
    private lateinit var suggestBox: LinearLayout
    private lateinit var favRowView: LinearLayout
    private lateinit var favChips: LinearLayout
    private lateinit var addFavBtn: Button
    private lateinit var instrCard: LinearLayout
    private lateinit var mapInstructionView: TextView
    private lateinit var mapInfoView: TextView
    private lateinit var voiceBtn: Button
    private lateinit var mapStyleBtn: Button
    private lateinit var mapTiltBtn: Button
    private lateinit var panelNightBtn: Button
    private lateinit var panelVoiceBtn: Button
    private lateinit var cancelBtn: Button

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
    // Posicao somente visual: ajustada a rota quando o GPS e suficientemente preciso.
    private var mapFix: Location? = null
    // Rumo estabilizado: o mesmo valor controla mapa e marcador da moto.
    private var stableHeading = 0.0
    private var headingInitialized = false
    private var previousHeadingFix: Location? = null

    private fun updateStableHeading(loc: Location) {
        if (!loc.hasSpeed() || loc.speed < 2.7f || (loc.hasAccuracy() && loc.accuracy > 35f)) return
        val old = previousHeadingFix
        val moveHeading = if (old != null && old.distanceTo(loc) >= 7f) old.bearingTo(loc).toDouble() else null
        val gpsHeading = if (loc.hasBearing() &&
            (!loc.hasBearingAccuracy() || loc.bearingAccuracyDegrees <= 35f)) loc.bearing.toDouble() else null
        if (moveHeading != null || old == null) previousHeadingFix = Location(loc)
        val target = when {
            gpsHeading != null && moveHeading != null -> {
                val mismatch = kotlin.math.abs(((gpsHeading - moveHeading + 540.0) % 360.0) - 180.0)
                if (mismatch > 85.0) moveHeading else gpsHeading
            }
            gpsHeading != null -> gpsHeading
            else -> moveHeading ?: return
        }
        if (!headingInitialized) {
            stableHeading = (target + 360.0) % 360.0
            headingInitialized = true
        } else {
            val delta = ((target - stableHeading + 540.0) % 360.0) - 180.0
            stableHeading = (stableHeading + delta * 0.42 + 360.0) % 360.0
        }
    }
    private val roadAlerts: RoadAlerts by lazy {
        RoadAlerts(this, { message -> say(message) }, { refreshRoadAlerts() }, { message -> toast(message) })
    }

    private fun refreshRoadAlerts() {
        val geo = roadAlerts.features()
        holders.forEach { h -> h.alertSrc?.setGeoJson(geo) }
    }

    // Limite da via vem de maxspeed do OpenStreetMap, NÃO do radar.
    // Sem uma via confiável cadastrada, a plaquinha fica escondida.
    private val roadSpeedSourceLazy = lazy {
        RoadSpeedLimitSource(this) { kmh -> updateRoadLimit(kmh) }
    }
    private val roadSpeedSource: RoadSpeedLimitSource
        get() = roadSpeedSourceLazy.value

    private fun updateRoadLimit(kmh: Int?) {
        currentRoadLimit = kmh
        if (::miniRoadLimitPlate.isInitialized && ::fullRoadLimitPlate.isInitialized) {
            val visible = if (kmh == null) View.GONE else View.VISIBLE
            miniRoadLimitPlate.visibility = visible
            fullRoadLimitPlate.visibility = visible
            if (kmh != null) {
                val plate = TrafficIcons.speedSign(kmh)
                miniRoadLimitIcon.setImageBitmap(plate)
                fullRoadLimitIcon.setImageBitmap(plate)
            }
        }
    }

    private fun roadLimitPlate(mini: Boolean): LinearLayout {
        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            currentRoadLimit?.let { setImageBitmap(TrafficIcons.speedSign(it)) }
        }
        val plate = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box(this@MainActivity, 0xDF0C1930.toInt(), 12)
            setPadding(px(6), px(3), px(8), px(3))
            addView(icon, LinearLayout.LayoutParams(px(42), px(42)))
            addView(label(12f, Color.WHITE, bold = true).apply {
                text = "VIA"
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = px(2) })
            visibility = if (currentRoadLimit == null) View.GONE else View.VISIBLE
        }
        if (mini) {
            miniRoadLimitIcon = icon
            miniRoadLimitPlate = plate
        } else {
            fullRoadLimitIcon = icon
            fullRoadLimitPlate = plate
        }
        return plate
    }
    private var steps: List<Step> = emptyList()
    private var stepIndex = 0
    private var announcedNear = false
    private var routePoints: List<LatLng> = emptyList()
    private var routeDest: LatLng? = null
    private var routeName = ""

    // Previsao OSRM: nao depende de trafego ao vivo. O GPS mede o progresso
    // e atualiza a distancia, o tempo restante e o horario de chegada.
    private var etaActive = false
    private var etaProfile: RouteEtaCalculator.Profile? = null
    private var routeEstimatedMeters = 0.0
    private var routeEstimatedSeconds = 0.0
    private var routeTraveledMeters = 0.0
    private var etaRemainingMeters = 0.0
    private var etaRemainingSeconds = 0.0
    private val arrivalFormat = SimpleDateFormat("HH:mm", Locale("pt", "BR"))

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
            refreshEtaPanels()
            updateTime()
            updateTripButtons()
            updateConnection()
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
            updateStableHeading(loc)
            lastFix = loc
            mapFix = mapDisplayLocation(loc)
            roadAlerts.onLocation(loc)
            roadSpeedSource.onLocation(loc)

            // Primeira localização: já posiciona o mapa
            if (!firstFixDone) {
                firstFixDone = true
                followMe = true
                holders.filter { (it.interactive && screenMode == 1) || (!it.interactive && screenMode == 0) }
                    .forEach { followCamera(it, mapFix ?: loc, false) }
            } else if (followMe) {
                // Camera e marcador usam EXATAMENTE o mesmo ponto no GPS.
                // Sem interpolacao longa que fazia a moto andar na diagonal.
                holders.filter { (it.interactive && screenMode == 1) || (!it.interactive && screenMode == 0) }
                    .forEach { followCamera(it, mapFix ?: loc, false) }
            }
            holders.forEach { updateMeMarker(it, mapFix ?: loc) }
            updateEtaFromGps(loc)
            onFix(loc)
            positionFullEtaCard()
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
        var alertSrc: GeoJsonSource? = null
        // Evita reenviar o mesmo marcador a cada GPS, causando reprocessamento.
        var lastMarkerPosition: Location? = null
        var lastMarkerScreenBearing = Double.NaN

        init {
            view.onCreate(null)
            view.onStart()
            view.onResume()
            view.getMapAsync { m ->
                map = m
                m.uiSettings.setAllGesturesEnabled(interactive)
                if (interactive) {
                    // A bússola nativa fica abaixo da barra de pesquisa (48dp + 12dp),
                    // com espaço extra para não encostar no campo de destino.
                    m.uiSettings.setCompassMargins(px(12), px(78), px(12), px(12))
                }
                if (interactive) {
                    m.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            followMe = false
                        }
                    }
                }
                if (interactive) {
                    // Mantem a previsao logo abaixo da moto ao mover ou girar o mapa.
                    m.addOnCameraMoveListener { positionFullEtaCard() }
                    m.addOnCameraIdleListener {
                        // Consultar a área que o usuário abriu no mapa inteiro, mesmo longe do GPS.
                        if (m.cameraPosition.zoom >= 11.0 && !followMe) {
                            m.cameraPosition.target?.let { target ->
                                roadAlerts.fetchAt(target.latitude, target.longitude)
                            }
                        }
                    }
                }
                m.setStyle(Style.Builder().fromUri(MAP_URL)) { s -> onStyleReady(this, s) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideStatusBar()
        MapLibre.getInstance(this)
        OfflineMaps.configure(this)

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

    // Esconde a barra de status (a de cima com hora e bateria do Android)
    @Suppress("DEPRECATION")
    private fun hideStatusBar() {
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    // Quando a janela volta a ter foco (ex.: depois de puxar a barra), esconde de novo
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
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
        updateConnection()
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
        roadAlerts.stop()
        if (roadSpeedSourceLazy.isInitialized()) roadSpeedSource.close()
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
        // isCharging isolado nem sempre indica cabo conectado em aparelhos Android.
        // ACTION_BATTERY_CHANGED e um broadcast fixo com status, tomada e percentual.
        val info = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = info?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = info?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val fromIntent = if (level >= 0 && scale > 0)
            ((level * 100f) / scale).roundToInt().coerceIn(0, 100) else -1
        val pct = if (fromIntent >= 0) fromIntent
            else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = info?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = info?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val connected = plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            bm.isCharging
        if (pct in 0..100) batteryView.update(pct, connected)
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
                background = box(context, cCard, 18, withAlpha(accent, 90))
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
        cTrack = Prefs.colorOf(this, "arc_track", 0xFF14284A.toInt())
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

    // ---------- Botões flutuantes do mapa (estilo azul-marinho semitransparente) ----------

    // Fundo normal (azul-marinho semitransparente) e efeito ao pressionar
    private fun floatBg(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), box(this@MainActivity, 0xEE1D3A66.toInt(), 14, 0x806FA8E8.toInt()))
        addState(intArrayOf(), box(this@MainActivity, 0xCC0B1A33.toInt(), 14, 0x553A6FB0))
    }

    // Centralização moderna: alvo vetorial, borda na cor do tema e área de toque maior.
    private fun modernRecenterButton(): Button = floatBtn("") { recenter() }.apply {
        panelIcon(R.drawable.ravo_recenter, Color.WHITE, 27)
        background = box(this@MainActivity, 0xEE15385B.toInt(), 18, accent)
        contentDescription = "Centralizar na minha localização"
        elevation = pxF(4f)
    }

    // Botão flutuante só com ícone
    private fun floatBtn(icon: String, onClick: () -> Unit): Button = Button(this).apply {
        text = icon
        textSize = 18f
        setTextColor(Color.WHITE)
        isAllCaps = false
        stateListAnimator = null
        background = floatBg()
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        includeFontPadding = false
        setOnClickListener { onClick() }
    }

    // Ativado: fundo na cor de destaque (azul elétrico por padrão). Desativado: fica mais apagado
    private fun setOn(b: Button, on: Boolean, enabled: Boolean = true) {
        b.background = if (on) box(this, accent, 14) else floatBg()
        b.setTextColor(Color.WHITE)
        b.alpha = if (enabled) 1f else 0.4f
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
                        // Hierarquia visual: vias locais discretas, avenidas mais claras,
                        // expressas em destaque. Não sobrescreve o contorno das ruas.
                        if (isRoad) {
                            val isOutline = listOf("casing", "outline", "border").any { id.contains(it) }
                            val roadColor = when {
                                id.contains("motorway") || id.contains("trunk") || id.contains("express") -> 0xFFFFC66D.toInt()
                                id.contains("primary") -> 0xFFFFD99B.toInt()
                                id.contains("secondary") || id.contains("tertiary") -> 0xFFB5D1E5.toInt()
                                else -> 0xFF718FA9.toInt()
                            }
                            layer.setProperties(PropertyFactory.lineColor(hex(
                                if (isOutline) 0xFF253D57.toInt() else roadColor
                            )))
                        } else if (!id.contains("route")) {
                            layer.setProperties(PropertyFactory.lineColor(hex(0xFF2A4A70.toInt())))
                        }
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

    // Prédios: o volume 3D depende só da opção "Prédios 3D" nas Configurações (não do botão de inclinação)
    private fun buildingsEnabled(): Boolean = Prefs.getInt(this, "buildings") == 1

    // Liga ou desliga o volume de TODAS as camadas de prédios do estilo (as que já vêm no mapa e a nossa)
    private fun applyBuildingVisibility(s: Style) {
        val vis = if (buildingsEnabled()) Property.VISIBLE else Property.NONE
        for (layer in s.layers) {
            if (layer is FillExtrusionLayer) {
                try {
                    layer.setProperties(PropertyFactory.visibility(vis))
                } catch (e: Exception) {
                    // Camada que não aceita a mudança continua como está
                }
            }
        }
    }

    // ---------- Mapa: criação, estilo e camadas ----------

    private fun newMap(interactive: Boolean): MapView {
        val h = MapHolder(interactive)
        holders.add(h)
        return h.view
    }

    private fun onStyleReady(h: MapHolder, s: Style) {
        h.style = s
        h.lastMarkerPosition = null
        h.lastMarkerScreenBearing = Double.NaN
        // Desliga o fade padrao dos simbolos a cada atualização dos dados/tiles.
        s.setTransition(TransitionOptions(0L, 0L, false))
        if (isNightNow()) applyNightTheme(s)
        s.addImage("me-icon", ravoPositionMarker(this, Prefs.getInt(this, "marker"), Prefs.colorOf(this, "marker_c", accent), Prefs.colorOf(this, "bag_c", 0xFF2EA8FF.toInt())))
        // Ícones próprios; registrados novamente a cada troca de estilo claro/escuro.
        for (kind in listOf("radar", "lombada", "semaforo")) {
            s.addImage("ravo-alert-$kind", TrafficIcons.load(this, kind))
        }
        // Sinais redondos com a velocidade do radar. Sem dado, sem plaquinha.
        for (limit in 10..140 step 5) {
            s.addImage("ravo-limit-" + limit, TrafficIcons.speedSign(limit))
        }

        val rs = GeoJsonSource("route-src")
        val ds = GeoJsonSource("dest-src")
        val ms = GeoJsonSource("me-src")
        val alerts = GeoJsonSource("alerts-src")
        h.alertSrc = alerts
        h.routeSrc = rs
        h.destSrc = ds
        h.meSrc = ms
        s.addSource(rs)
        s.addSource(ds)
        s.addSource(ms)
        s.addSource(alerts)

        // Prédios em 3D: usa a fonte de dados vetoriais do estilo atual (se o estilo não tiver, segue em 2D)
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
                        PropertyFactory.fillExtrusionOpacity(0.9f)
                    )
                s.addLayer(b)
            } catch (e: Exception) {
                // Se o estilo não tiver a camada de prédios, o mapa continua sem ela
            }
        }
        applyBuildingVisibility(s)

        // Rota laranja de alto contraste, com contorno escuro para destacar
        // o caminho tanto no mapa claro quanto no mapa noturno.
        s.addLayer(
            LineLayer("route-outline-layer", "route-src").withProperties(
                PropertyFactory.lineColor(0xFF152236.toInt()),
                PropertyFactory.lineWidth(20f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        s.addLayer(
            LineLayer("route-layer", "route-src").withProperties(
                PropertyFactory.lineColor(0xFFFF7139.toInt()),
                PropertyFactory.lineWidth(14f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        // Ícones claros e reconhecíveis, sem bolinhas genéricas.
        // Fora da rota discretos, na rota maiores; o próximo alerta ganha halo.
        val next = Expression.eq(Expression.get("priority"), Expression.literal("next"))
        s.addLayer(
            CircleLayer("next-hazard-halo", "alerts-src").withFilter(next).withProperties(
                PropertyFactory.circleColor(0xFFFFC107.toInt()),
                PropertyFactory.circleRadius(24f),
                PropertyFactory.circleOpacity(0.22f),
                PropertyFactory.circleStrokeColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(2f)
            )
        )
        for (kind in listOf("radar", "lombada", "semaforo")) {
            for (priority in listOf("other", "route", "next")) {
                val scale = when (priority) {
                    "next" -> if (kind == "semaforo") 0.94f else 1.15f
                    "route" -> if (kind == "semaforo") 0.74f else 0.94f
                    else -> if (kind == "semaforo") 0.54f else 0.69f
                }
                val alpha = if (priority == "other") 0.72f else 1f
                s.addLayer(
                    SymbolLayer("ravo-$kind-$priority", "alerts-src").withFilter(
                        Expression.all(
                            Expression.eq(Expression.get("type"), Expression.literal(kind)),
                            Expression.eq(Expression.get("priority"), Expression.literal(priority))
                        )
                    ).withProperties(
                        PropertyFactory.iconImage("ravo-alert-$kind"),
                        PropertyFactory.iconSize(scale),
                        PropertyFactory.iconOpacity(alpha),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true)
                    )
                )
            }
        }
        // A placa numérica aparece AO LADO do ícone, nos dois mapas.
        // É o limite fiscalizado pelo radar, independente do limite da via.
        for (priority in listOf("other", "route", "next")) {
            val plateSize = when (priority) {
                "next" -> 0.72f
                "route" -> 0.62f
                else -> 0.52f
            }
            val sideOffset = when (priority) {
                "next" -> 124f
                "route" -> 120f
                else -> 112f
            }
            s.addLayer(
                SymbolLayer("ravo-radar-speed-$priority", "alerts-src").withFilter(
                    Expression.all(
                        Expression.eq(Expression.get("type"), Expression.literal("radar")),
                        Expression.eq(Expression.get("priority"), Expression.literal(priority)),
                        Expression.has("speedSign")
                    )
                ).withProperties(
                    PropertyFactory.iconImage(Expression.get("speedSign")),
                    PropertyFactory.iconSize(plateSize),
                    PropertyFactory.iconOffset(arrayOf(sideOffset, 0f)),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT)
                )
            )
        }
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
                PropertyFactory.iconRotate(Expression.get("screenBearing")),
                // O icone ja recebe angulo relativo a camera, ficando alinhado a via
                // em vez de acompanhar uma rotacao diagonal inesperada.
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT)
            )
        )

        refreshRoute(h)
        alerts.setGeoJson(roadAlerts.features())
        (mapFix ?: lastFix)?.let { loc ->
            followCamera(h, loc, false)
            updateMeMarker(h, loc)
        } ?: h.map?.let { m ->
            m.moveCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(m.cameraPosition).tilt(if (is3D) 60.0 else 0.0).build()
            ))
        }
    }

    // O ponto usado somente para o mapa pode ser projetado na rota conhecida.
    // GPS e velocidade continuam com a medicao original, sem alterar a navegacao.
    private fun mapDisplayLocation(loc: Location): Location {
        if (routeDest == null || routePoints.size < 2 ||
            (loc.hasAccuracy() && loc.accuracy > 30f)) return loc
        val latScale = 111320.0
        val lonScale = 111320.0 * cos(Math.toRadians(loc.latitude))
        var bestDistance = Double.MAX_VALUE
        var bestLat = loc.latitude
        var bestLon = loc.longitude
        for (i in 0 until routePoints.lastIndex) {
            val a = routePoints[i]
            val b = routePoints[i + 1]
            val dx = (b.longitude - a.longitude) * lonScale
            val dy = (b.latitude - a.latitude) * latScale
            val len2 = dx * dx + dy * dy
            if (len2 < 0.01) continue
            val px = (loc.longitude - a.longitude) * lonScale
            val py = (loc.latitude - a.latitude) * latScale
            val t = ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
            val diffX = px - t * dx
            val diffY = py - t * dy
            val dist2 = diffX * diffX + diffY * diffY
            if (dist2 < bestDistance) {
                bestDistance = dist2
                bestLat = a.latitude + t * (b.latitude - a.latitude)
                bestLon = a.longitude + t * (b.longitude - a.longitude)
            }
        }
        if (bestDistance > 20.0 * 20.0) return loc
        return Location(loc).apply {
            latitude = bestLat
            longitude = bestLon
        }
    }

    // Perspectiva de navegacao tipo Waze: seu veiculo no centro horizontal,
    // perto da parte inferior, com mais espaco para enxergar o caminho a frente.
    private fun followCamera(h: MapHolder, loc: Location, animate: Boolean) {
        val m = h.map ?: return
        val zoom = if (h.interactive) 17.0 else 16.0
        val bearing = if (is3D && headingInitialized) stableHeading else 0.0
        val viewHeight = h.view.height.takeIf { it > 0 } ?: px(320)
        val pos = CameraPosition.Builder()
            .target(LatLng(loc.latitude, loc.longitude))
            .zoom(zoom)
            .tilt(if (is3D) 60.0 else 0.0)
            .bearing(bearing)
            .padding(0.0, viewHeight * 0.48, 0.0, 0.0)
            .build()
        if (animate) {
            m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 500)
        } else {
            // Nao deixar a moto ir na diagonal enquanto a camera interpola entre GPSs.
            m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
        }
    }

    private fun updateMeMarker(h: MapHolder, loc: Location) {
        val src = h.meSrc ?: return
        val cameraBearing = h.map?.cameraPosition?.bearing ?: 0.0
        val screenBearing = ((stableHeading - cameraBearing + 540.0) % 360.0) - 180.0
        val prev = h.lastMarkerPosition
        val change = if (prev != null) prev.distanceTo(loc) else Float.MAX_VALUE
        val deltaBearing = if (h.lastMarkerScreenBearing.isFinite()) {
            kotlin.math.abs(((screenBearing - h.lastMarkerScreenBearing + 540.0) % 360.0) - 180.0)
        } else Double.MAX_VALUE
        if (change < 1.3f && deltaBearing < 2.0) return
        h.lastMarkerPosition = Location(loc)
        h.lastMarkerScreenBearing = screenBearing
        val feature = Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude))
        feature.addNumberProperty("screenBearing", screenBearing)
        src.setGeoJson(feature)
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

    // Inclina ou volta o mapa (independente da opção de prédios)
    private fun toggleNightOnPanel() {
        mapMode = if (isNightNow()) 1 else 2
        Prefs.putRaw(this, "map_style", mapMode)
        applyMapStyle()
        refreshMapButtons()
    }

    private fun toggleVoice() {
        voiceOn = !voiceOn
        if (!voiceOn) tts?.stop()
        refreshMapButtons()
    }

    private fun toggle3D() {
        is3D = !is3D
        holders.forEach { h ->
            val m = h.map
            if (m != null) {
                if (followMe) {
                    (mapFix ?: lastFix)?.let { followCamera(h, it, true) }
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
        refreshMapButtons()
    }

    private fun modeLabel(): String = if (is3D) "2D" else "3D"

    // Centraliza o mapa completo na sua posição e volta a seguir
    private fun recenter() {
        val loc = mapFix ?: lastFix
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
                    textSize = 14f
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

    // Mostra ou esconde a faixa de favoritos (ativada pelo ★ da busca)
    private fun toggleFavRow() {
        if (!::favRowView.isInitialized) return
        favRowView.visibility = if (favRowView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        refreshFavChips()
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
        musicDock.visibility = if (mode == 0 || mode == 1) View.VISIBLE else View.GONE
        // Atualiza a câmera ao trocar de mapa, já que mapas ocultos não são animados.
        if (mode == 0 || mode == 1) {
            (mapFix ?: lastFix)?.let { loc ->
                if (followMe) holders.filter { it.interactive == (mode == 1) }
                    .forEach { followCamera(it, loc, false) }
            }
        }
        if (mode == 1) fullEtaCard.post { positionFullEtaCard() }
        if (mode == 2) refreshTripsList()
    }

    // ---------- Navegação ----------

    private fun updateEtaFromGps(loc: Location) {
        val profile = etaProfile ?: return
        if (!etaActive || (loc.hasAccuracy() && loc.accuracy > 65f)) return
        val projection = RouteEtaCalculator.project(profile, loc.latitude, loc.longitude) ?: return
        // Fora da via: mantem a ultima previsao ate a rota ser recalculada.
        if (projection.offRouteMeters > 55.0) return
        // Evita que ruído do GPS faça os quilômetros voltarem a aumentar.
        routeTraveledMeters = maxOf(routeTraveledMeters, projection.progressMeters)
        val remaining = RouteEtaCalculator.fractionRemaining(profile, routeTraveledMeters)
        etaRemainingMeters = RouteEtaCalculator.distanceRemaining(routeEstimatedMeters, remaining)
        etaRemainingSeconds = RouteEtaCalculator.durationRemaining(routeEstimatedSeconds, remaining)
        refreshEtaPanels()
    }

    private fun refreshEtaPanels() {
        if (!::panelEtaCard.isInitialized || !::fullEtaCard.isInitialized) return
        if (!etaActive || routeDest == null) {
            panelEtaCard.visibility = View.GONE
            fullEtaCard.visibility = View.GONE
            return
        }
        val minuteCount = ceil(etaRemainingSeconds.coerceAtLeast(0.0) / 60.0)
            .toInt().coerceAtLeast(1)
        val durationText = if (minuteCount >= 60) {
            "${minuteCount / 60}h ${minuteCount % 60}min"
        } else "${minuteCount} min"
        val distanceText = String.format(Locale("pt", "BR"), "%.1f km",
            etaRemainingMeters.coerceAtLeast(0.0) / 1000.0)
        val arrival = arrivalFormat.format(
            Date(System.currentTimeMillis() + (etaRemainingSeconds * 1000.0).toLong())
        )
        panelEtaCard.render(arrival, durationText, distanceText)
        fullEtaCard.render(arrival, durationText, distanceText)
        panelEtaCard.visibility = View.VISIBLE
        if (screenMode == 1) fullEtaCard.post { positionFullEtaCard() }
    }

    // A bolha fica abaixo da motinho *na tela*, inclusive quando o mapa gira
    // ou e arrastado. Nao substitui o icone e nem desloca a camera.
    private fun positionFullEtaCard() {
        if (!::fullEtaCard.isInitialized || !::fullMap.isInitialized ||
            !etaActive || screenMode != 1) return
        val loc = mapFix ?: lastFix ?: return
        val map = holders.firstOrNull { it.interactive }?.map ?: return
        val w = fullMap.width
        val h = fullMap.height
        val cardW = fullEtaCard.width
        val cardH = fullEtaCard.height
        if (w < px(160) || h < px(200) || cardW <= 0 || cardH <= 0) return
        val marker = map.projection.toScreenLocation(LatLng(loc.latitude, loc.longitude))
        // Se o usuario abriu o mapa longe da sua posicao, oculta o cartao.
        if (marker.x < -px(16) || marker.x > w + px(16) ||
            marker.y < -px(16) || marker.y > h + px(16)) {
            fullEtaCard.visibility = View.INVISIBLE
            return
        }
        val maxX = (w - cardW - px(8)).coerceAtLeast(px(8)).toFloat()
        val maxY = (h - cardH - px(102)).coerceAtLeast(px(130)).toFloat()
        fullEtaCard.x = (marker.x - cardW / 2f).coerceIn(px(8).toFloat(), maxX)
        fullEtaCard.y = (marker.y + px(38)).coerceIn(px(130).toFloat(), maxY)
        fullEtaCard.visibility = View.VISIBLE
    }

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
                // A rota pode continuar desenhada, mas os dados de ETA somem ao chegar.
                roadAlerts.stopNavigation()
                etaActive = false
                refreshEtaPanels()
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
        refreshMapButtons()
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
        etaActive = points.size > 1 && list.isNotEmpty()
        etaProfile = RouteEtaCalculator.profile(points.map {
            RouteEtaCalculator.Coordinate(it.latitude, it.longitude)
        })
        routeEstimatedMeters = totalM.coerceAtLeast(0.0)
        routeEstimatedSeconds = durS.coerceAtLeast(0.0)
        routeTraveledMeters = 0.0
        etaRemainingMeters = routeEstimatedMeters
        etaRemainingSeconds = routeEstimatedSeconds
        lastFix?.let { updateEtaFromGps(it) }
        steps = list
        announcedNear = false
        offCount = 0
        lastRecalcMs = System.currentTimeMillis()
        // O primeiro passo começa no seu ponto atual, então pulamos ele
        stepIndex = if (list.size > 1) 1 else 0

        holders.forEach { refreshRoute(it) }
        roadAlerts.setRoute(if (list.isNotEmpty()) points else emptyList())

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
        refreshEtaPanels()
        refreshMapButtons()
    }

    private fun clearRoute() {
        etaActive = false
        etaProfile = null
        routeEstimatedMeters = 0.0
        routeEstimatedSeconds = 0.0
        etaRemainingMeters = 0.0
        etaRemainingSeconds = 0.0
        routeTraveledMeters = 0.0
        routePoints = emptyList()
        routeDest = null
        steps = emptyList()
        stepIndex = 0
        offCount = 0
        holders.forEach { refreshRoute(it) }
        roadAlerts.setRoute(emptyList())

        mapInstructionView.text = ""
        mapInfoView.text = ""
        navView.text = ""
        navView.visibility = View.GONE
        refreshEtaPanels()
        refreshMapButtons()
    }

    // Tem rota ativa? Só então aparecem o cancelar rota, o cartão de instrução e o salvar favorito
    private fun hasRoute(): Boolean = routeDest != null

    // Atualiza o destaque e a visibilidade dos botões flutuantes do mapa
    private fun refreshMapButtons() {
        if (!::mapStyleBtn.isInitialized) return
        // Voz sincronizada entre velocimetro e mapa inteiro.
        listOf(voiceBtn, panelVoiceBtn).forEach { button ->
            button.text = ""
            button.contentDescription = if (voiceOn) "Desativar instruções de voz"
                else "Ativar instruções de voz"
            val speaker = getDrawable(
                if (voiceOn) R.drawable.ravo_voice_on else R.drawable.ravo_voice_off
            )?.mutate()
            speaker?.setBounds(0, 0, px(24), px(24))
            button.setCompoundDrawables(null, null, null, null)
            button.foregroundGravity = Gravity.CENTER
            button.foreground = speaker
            setOn(button, voiceOn)
        }
        mapStyleBtn.text = when (mapMode) {
            1 -> "☀"
            2 -> "☾"
            else -> "◐"
        }
        setOn(mapStyleBtn, mapMode != 0)
        panelNightBtn.text = if (isNightNow()) "☀" else "☾"
        panelNightBtn.contentDescription = if (isNightNow()) "Usar mapa claro" else "Usar modo noturno"
        setOn(panelNightBtn, isNightNow())
        // Mesma indicação do botão do velocímetro: mostra o modo para o qual alterna.
        mapTiltBtn.text = modeLabel()
        mapTiltBtn.contentDescription = "Alternar mapa para ${modeLabel()}"
        mapTiltBtn.setTextColor(accent)
        cancelBtn.visibility = if (hasRoute()) View.VISIBLE else View.GONE
        addFavBtn.visibility = if (hasRoute()) View.VISIBLE else View.GONE
        instrCard.visibility = if (hasRoute() && mapInstructionView.text.isNotEmpty()) View.VISIBLE else View.GONE
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
        conn.setRequestProperty("User-Agent", "RavoApp/1.1 (uso pessoal)")
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        return conn.inputStream.bufferedReader().use { it.readText() }
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

    // Valor de estatística: uma linha só, com o texto diminuindo para caber
    private fun statValue(text: String): TextView =
        label(20f, cStat, bold = true, mono = true, align = Gravity.CENTER).apply {
            this.text = text
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(10, 22, 1, TypedValue.COMPLEX_UNIT_SP)
        }

    private fun pill(caption: String, bg: Int, fg: Int, sizeSp: Float = 16f): Button = Button(this).apply {
        text = caption
        textSize = sizeSp
        setTextColor(fg)
        isAllCaps = false
        stateListAnimator = null
        background = box(context, bg, 16, withAlpha(fg, 45))
        minHeight = 0
        minimumHeight = 0
    }

    // Ícones vetoriais nítidos com a cor de destaque personalizada.
    private fun Button.panelIcon(res: Int, color: Int = accent, size: Int = 21) {
        val d = this@MainActivity.getDrawable(res)?.mutate() ?: return
        d.setTint(color)
        d.setBounds(0, 0, px(size), px(size))
        if (text.isNullOrBlank()) {
            // Drawable no foreground: centralizado na área inteira do botão,
            // sem depender da altura da linha de texto (que deslocava o ícone).
            setCompoundDrawables(null, null, null, null)
            text = ""
            setPadding(0, 0, 0, 0)
            foregroundGravity = Gravity.CENTER
            foreground = d
        } else {
            // Botões com legenda (Salvar/Zerar) mantêm ícone ao lado do texto.
            setCompoundDrawables(d, null, null, null)
            compoundDrawablePadding = px(5)
        }
        gravity = Gravity.CENTER
    }

    // Botões compactos de navegação
    private fun miniBtn(caption: String): Button = Button(this).apply {
        text = caption
        textSize = 16f
        setTextColor(Color.WHITE)
        isAllCaps = false
        stateListAnimator = null
        background = box(context, BTN_BG, 16, withAlpha(accent, 105))
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
    }

    private fun statCard(title: String, value: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = box(context, cCard, 14, cBorder)
        setPadding(px(6), px(12), px(6), px(12))
        addView(label(11f, accent, bold = true, align = Gravity.CENTER).apply { text = title; letterSpacing = 0.07f })
        addView(value, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun buildUi(): FrameLayout {
        // Desfaz os mapas antigos antes de montar a tela de novo
        destroyHolders()
        modeBtns.clear()

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        loadColors()
        val bgStyle = Prefs.getInt(this, "bg")

        val frame = FrameLayout(this).apply { background = bgDrawable(bgStyle) }
        if (bgStyle == 0) frame.addView(GridView(this, cGrid), FrameLayout.LayoutParams(MATCH, MATCH))

        panelScreen = buildPanel(landscape)
        mapScreen = buildMapScreen()
        tripsScreen = buildTripsScreen()
        frame.addView(panelScreen, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(mapScreen, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(tripsScreen, FrameLayout.LayoutParams(MATCH, MATCH))

        // Player: bloco que fica no lugar do botão ♪ (ele mesmo calcula a posição)
        frame.addView(musicDock, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        musicDock.post { applyFabLayout() }

        setScreen(screenMode)
        updateTripButtons()
        refreshEtaPanels()
        refreshMapButtons()
        return frame
    }

    private fun buildPanel(landscape: Boolean): LinearLayout {
        val gaugeStyle = Prefs.getInt(this, "gauge")
        val nick = Prefs.getStr(this, "nick")
        val brandModel = listOf(Prefs.getStr(this, "brand"), Prefs.getStr(this, "model"))
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val root = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(16))
        }

        // Barra superior: relógio, GPS e, à direita, data e bateria
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        clockView = label(20f, cClock, bold = true, mono = true)
        gpsView = label(12f, cLabel, align = Gravity.CENTER)
        dateView = label(12f, cLabel, align = Gravity.END)
        batteryView = BatteryStatusView(this, cLabel)
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(dateView, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(batteryView, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        topBar.addView(clockView, LinearLayout.LayoutParams(0, WRAP, 1f))
        topBar.addView(gpsView, LinearLayout.LayoutParams(0, WRAP, 1.6f))
        topBar.addView(rightCol, LinearLayout.LayoutParams(0, WRAP, 1f))

        // Cartao de chegada acima do velocimetro, visivel somente na navegacao.
        panelEtaCard = RouteEtaCard(this, accent)

        // Veículo: apelido grande e centralizado; marca e modelo menores abaixo
        nickView = label(24f, accent, bold = true, align = Gravity.CENTER).apply {
            text = nick
            visibility = if (nick.isEmpty()) View.GONE else View.VISIBLE
        }
        modelView = label(14f, cLabel, align = Gravity.CENTER).apply {
            text = brandModel
            visibility = if (brandModel.isEmpty()) View.GONE else View.VISIBLE
        }
        vehicleView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = if (nick.isEmpty() && brandModel.isEmpty()) View.GONE else View.VISIBLE
            addView(nickView, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(modelView, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        // Velocímetro: miniatura do mapa ao fundo, arco e velocidade por cima
        minimap = newMap(false)
        minimap.alpha = Prefs.intOr(this, "gauge_map_opacity", 35).coerceIn(15, 80) / 100f
        arcView = SpeedArcView(this).apply {
            setZones(cArc1, cArc2, cArc3, arcLim1, arcLim2, cTrack)
            configure(gaugeStyle, Prefs.intOr(this@MainActivity, "gauge_stroke", 12),
                Prefs.intOr(this@MainActivity, "gauge_max", 200))
        }
        arcView.visibility = if (gaugeStyle == 1 || gaugeStyle == 5) View.GONE else View.VISIBLE
        val digital = gaugeStyle == 1 || gaugeStyle == 5
        speedView = label(
            if (digital) (if (landscape) 110f else 120f) else (if (landscape) 64f else 84f),
            cNum,
            bold = true,
            align = Gravity.CENTER
        ).apply {
            text = "0"
            maxLines = 1
            if (gaugeStyle == 5) {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = 0.08f
                setShadowLayer(pxF(7f), 0f, 0f, accent)
            }
        }
        unitView = label(16f, cUnit, align = Gravity.CENTER).apply { text = unitSpeed() }
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
            // Sobreposto ao minimapa, sem interferir nos números do velocímetro.
            addView(roadLimitPlate(true), FrameLayout.LayoutParams(WRAP, WRAP,
                Gravity.TOP or Gravity.END).apply {
                    topMargin = px(8)
                    rightMargin = px(8)
                })
        }

        // Mesmos controles do mapa completo na tela inicial: centralizar, noite e voz.
        val miniCenterBtn = modernRecenterButton()
        panelNightBtn = floatBtn("☾") { toggleNightOnPanel() }.apply {
            textSize = 23f
            contentDescription = "Alternar modo noturno"
        }
        panelVoiceBtn = floatBtn("") { toggleVoice() }.apply {
            contentDescription = "Alternar instruções de voz"
        }
        arcBlock.addView(miniCenterBtn, FrameLayout.LayoutParams(px(58), px(58),
            Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = px(12)
            bottomMargin = px(10)
        })
        arcBlock.addView(panelNightBtn, FrameLayout.LayoutParams(px(48), px(48),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(14) })
        arcBlock.addView(panelVoiceBtn, FrameLayout.LayoutParams(px(48), px(48),
            Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = px(12)
            bottomMargin = px(14)
        })

        // Linha de navegação: fica numa linha só e desliza se for longa
        navView = label(15f, cClock, bold = true, align = Gravity.CENTER).apply {
            visibility = View.GONE
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            isSelected = true
        }

        // Cabeçalho da trip: mostra o status (em andamento, pausada, parada)
        tripHeader = label(12f, cClock, bold = true, align = Gravity.CENTER)

        // Estatísticas da trip: na paisagem ficam empilhadas ao lado do mapa; na vertical, lado a lado
        distView = statValue(distText(totalM))
        timeView = statValue(fmtHMS(elapsedMs()))
        maxView = statValue(speedText(maxSpeed))
        val statsBox = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        listOf(
            Pair("DISTÂNCIA", distView),
            Pair("TEMPO", timeView),
            Pair("MÁX.", maxView)
        ).forEach { (title, value) ->
            val card = statCard(title, value)
            val lp = if (landscape) {
                LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, px(4), 0, px(4)) }
            } else {
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(px(4), 0, px(4), 0) }
            }
            statsBox.addView(card, lp)
        }

        // Botões da trip: Iniciar/Finalizar, pausa manual (pequeno), Salvar e Zerar
        tripBtn = pill("▶ Iniciar", accent, Color.BLACK, 15f).apply { typeface = Typeface.DEFAULT_BOLD }
        tripBtn.setOnClickListener {
            if (tripStarted) finishTrip() else startTrip()
        }
        pauseBtn = miniBtn("").apply { panelIcon(R.drawable.ravo_panel_pause, Color.WHITE) }
        pauseBtn.textSize = 14f
        pauseBtn.setOnClickListener { toggleManualPause() }
        saveBtn = pill("Salvar", BTN_BG, Color.WHITE, 14f).apply { panelIcon(R.drawable.ravo_panel_save, accent, 16) }
        saveBtn.setOnClickListener { saveTrip() }
        val resetBtn = pill("Zerar", BTN_BG, cLabel, 14f).apply { panelIcon(R.drawable.ravo_panel_reset, cLabel, 16) }
        resetBtn.setOnClickListener { resetTrip() }
        // Impede quebra de texto e distribui o espaço entre os botões em telas estreitas.
        listOf(tripBtn, saveBtn, resetBtn).forEach {
            it.isSingleLine = true
            it.ellipsize = android.text.TextUtils.TruncateAt.END
            it.includeFontPadding = false
        }
        val tripRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tripRow.addView(tripBtn, LinearLayout.LayoutParams(0, px(44), 1.55f).apply { setMargins(px(2), 0, px(2), 0) })
        tripRow.addView(pauseBtn, LinearLayout.LayoutParams(px(36), px(44)).apply { setMargins(0, 0, px(2), 0) })
        tripRow.addView(saveBtn, LinearLayout.LayoutParams(0, px(44), 1.25f).apply { setMargins(0, 0, px(2), 0) })
        tripRow.addView(resetBtn, LinearLayout.LayoutParams(0, px(44), 1.15f).apply { setMargins(0, 0, px(2), 0) })

        // Player: capa, título e artista (fica dentro do painel flutuante)
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
        artistView = label(12f, cLabel).apply {
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

        // Controles de música (ficam dentro do painel flutuante)
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

        // Painel do player (escondido até tocar no botão ♪)
        musicPopup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(context, cCard, 18, cBorder)
            setPadding(px(10), px(10), px(10), px(10))
            visibility = if (musicOpen) View.VISIBLE else View.GONE
            addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(controls, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) })
        }
        // Quando o player muda de tamanho, recalcula a posição para não sair da tela
        musicPopup.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            musicDock.post { applyFabLayout() }
        }

        // Botão flutuante ♪ / ✕
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
            setOnTouchListener(fabTouch)
        }

        // Bloco do player: botão e painel. A ordem e o alinhamento são definidos em applyFabLayout
        musicDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        musicDock.addView(musicPopup, LinearLayout.LayoutParams(px(280), WRAP))
        musicDock.addView(musicFab, LinearLayout.LayoutParams(px(56), px(56)))

        // Botões pequenos: mapa, trips salvas, configurações, 2D/3D e rotação
        val mapBtn = miniBtn("").apply { panelIcon(R.drawable.ravo_menu_map) }
        mapBtn.setOnClickListener { setScreen(1) }
        val tripsBtn = miniBtn("").apply { panelIcon(R.drawable.ravo_panel_trips) }
        tripsBtn.setOnClickListener { setScreen(2) }
        val settingsBtn = miniBtn("").apply { panelIcon(R.drawable.ravo_panel_settings) }
        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        val mode3dBtn = miniBtn(modeLabel()).apply { typeface = Typeface.DEFAULT_BOLD; setTextColor(accent) }
        modeBtns.add(mode3dBtn)
        mode3dBtn.setOnClickListener { toggle3D() }
        val rotateBtn = miniBtn("").apply { panelIcon(R.drawable.ravo_panel_orientation); contentDescription = "Orientação da tela" }
        rotateBtn.setOnClickListener {
            orientMode = (orientMode + 1) % 3
            applyOrientation()
            rotateBtn.contentDescription = "Orientação: " + orientLabel()
        }
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        navRow.addView(mapBtn, LinearLayout.LayoutParams(px(48), px(44)).apply { setMargins(px(3), 0, px(3), 0) })
        navRow.addView(tripsBtn, LinearLayout.LayoutParams(px(48), px(44)).apply { setMargins(px(3), 0, px(3), 0) })
        navRow.addView(settingsBtn, LinearLayout.LayoutParams(px(48), px(44)).apply { setMargins(px(3), 0, px(3), 0) })
        navRow.addView(mode3dBtn, LinearLayout.LayoutParams(px(48), px(44)).apply { setMargins(px(3), 0, px(3), 0) })
        navRow.addView(rotateBtn, LinearLayout.LayoutParams(px(48), px(44)).apply { setMargins(px(3), 0, px(3), 0) })

        fun gap(dp: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(dp) }

        if (landscape) {
            // Paisagem: mapa maior à esquerda, dados empilhados à direita (ocupam a altura toda)
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
            left.addView(panelEtaCard, gap(6))
            left.addView(vehicleView, gap(0))
            left.addView(arcBlock, LinearLayout.LayoutParams(MATCH, 0, 1f))

            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            right.addView(navView, gap(0))
            right.addView(tripHeader, gap(4))
            right.addView(statsBox, gap(4))
            right.addView(tripRow, gap(8))
            right.addView(navRow, gap(10))

            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(right, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            root.addView(left, LinearLayout.LayoutParams(0, MATCH, 1.8f))
            root.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = px(12) })
        } else {
            // Retrato: o player fica só no botão flutuante, por isso não entra aqui
            root.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
            root.addView(panelEtaCard, gap(6))
            root.addView(vehicleView, gap(0))
            root.addView(arcBlock, LinearLayout.LayoutParams(MATCH, 0, 1.4f))
            root.addView(navView, gap(4))
            root.addView(tripHeader, gap(8))
            root.addView(statsBox, gap(4))
            root.addView(tripRow, gap(8))
            root.addView(navRow, gap(10))
        }
        return root
    }

    // Página de trips salvas (máximo de 3)
    private fun buildTripsScreen(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF06142B.toInt())
            setPadding(px(16), px(16), px(16), px(16))
        }
        root.addView(label(22f, Color.WHITE, bold = true).apply { text = "Trips salvas" })
        root.addView(
            label(13f, cLabel).apply {
                text = "Você pode guardar até 3 trips. Ao salvar uma quarta, a mais antiga é removida."
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(4) }
        )

        tripsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(tripsList, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = px(8) })

        val backBtn = pill("Voltar ao painel", BTN_BG, Color.WHITE, 15f)
        backBtn.setOnClickListener { setScreen(0) }
        root.addView(backBtn, LinearLayout.LayoutParams(MATCH, px(48)).apply { topMargin = px(8) })
        return root
    }

    // Tela do mapa em tela cheia: o mapa ocupa tudo e os controles ficam flutuando por cima
    private fun buildMapScreen(): FrameLayout {
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFF06142B.toInt()) }

        // Mapa ocupa 100% da área
        fullMap = newMap(true)
        frame.addView(fullMap, FrameLayout.LayoutParams(MATCH, MATCH))
        // Limite da via em placa flutuante, longe da pesquisa e dos controles.
        frame.addView(roadLimitPlate(false), FrameLayout.LayoutParams(WRAP, WRAP,
            Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = px(16)
                bottomMargin = px(106)
            })
        offlineNotice = label(12f, Color.WHITE, bold = true).apply {
            text = "SEM INTERNET · mapa salvo / cache"
            setPadding(px(12), px(8), px(12), px(8))
            background = box(context, 0xDDAD391B.toInt(), 10)
            visibility = if (isConnected == false) View.VISIBLE else View.GONE
        }
        frame.addView(offlineNotice, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = px(104)
        })

        // ----- Topo: pesquisa minimalista, favoritos e cartão de instrução -----
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = floatBg()
            setPadding(px(12), 0, px(4), 0)
        }
        searchBox.addView(
            TextView(this).apply {
                text = "⌕"
                textSize = 18f
                setTextColor(cLabel)
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = px(8) }
        )
        searchInput = EditText(this).apply {
            hint = "Pesquisar"
            setHintTextColor(GRAY)
            setTextColor(Color.WHITE)
            textSize = 15f
            background = null
            setSingleLine(true)
            setPadding(0, px(10), 0, px(10))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(searchWatcher)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    go()
                    true
                } else false
            }
        }
        searchBox.addView(searchInput, LinearLayout.LayoutParams(0, WRAP, 1f))
        searchBox.addView(
            floatBtn("★") { toggleFavRow() },
            LinearLayout.LayoutParams(px(40), px(40))
        )

        // Sugestões (aparecem enquanto você digita)
        suggestBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(context, 0xEE0B1A33.toInt(), 14, 0x553A6FB0)
        }
        suggestScroll = ScrollView(this).apply {
            visibility = View.GONE
            addView(suggestBox)
        }

        // Faixa de favoritos (abre pelo ★) e botão para salvar o destino atual
        favChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val favScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(favChips)
        }
        addFavBtn = floatBtn("＋") { askFavNick() }
        favRowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box(context, 0xEE0B1A33.toInt(), 14, 0x553A6FB0)
            setPadding(px(6), px(4), px(6), px(4))
            visibility = View.GONE
            addView(favScroll, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(addFavBtn, LinearLayout.LayoutParams(px(40), px(40)))
        }

        // Cartão de instrução (só aparece com rota ativa)
        mapInstructionView = label(18f, Color.WHITE, bold = true).apply { maxLines = 2 }
        mapInfoView = label(13f, cClock)
        instrCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(context, 0xEE0B1A33.toInt(), 14, 0x553A6FB0)
            setPadding(px(14), px(10), px(14), px(10))
            visibility = View.GONE
            addView(mapInstructionView, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(mapInfoView, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        val topCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(12), px(12), 0)
            addView(searchBox, LinearLayout.LayoutParams(MATCH, px(48)))
            addView(suggestScroll, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(6) })
            addView(favRowView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(6) })
            addView(instrCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(6) })
        }
        frame.addView(topCol, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        // ----- Lateral direita: voz, modo noturno e inclinação 3D (só ícones) -----
        voiceBtn = floatBtn("") { toggleVoice() }
        mapStyleBtn = floatBtn("◐") {
            mapMode = (mapMode + 1) % 3
            Prefs.putRaw(this, "map_style", mapMode)
            applyMapStyle()
            refreshMapButtons()
        }
        // Igual ao botão 2D/3D do painel do velocímetro, sem símbolo diferente.
        mapTiltBtn = floatBtn(modeLabel()) { toggle3D() }.apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 16f
            setTextColor(accent)
            contentDescription = "Alternar mapa para ${modeLabel()}"
        }
        val mapRotateBtn = floatBtn("") {
            orientMode = (orientMode + 1) % 3
            applyOrientation()
        }.apply {
            panelIcon(R.drawable.ravo_panel_orientation)
            contentDescription = "Orientação: " + orientLabel()
        }
        val offlineSaveBtn = floatBtn("↓") {
            if (!online()) {
                toast("Conecte à internet para salvar uma área offline")
            } else {
                val target = holders.firstOrNull { it.interactive }?.map?.cameraPosition?.target
                if (target != null) {
                    OfflineMaps.saveNearby(this, target) { message -> toast(message) }
                } else toast("Mapa ainda não está pronto")
            }
        }.apply { contentDescription = "Salvar esta área para usar sem internet" }
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(voiceBtn, LinearLayout.LayoutParams(px(46), px(46)).apply { setMargins(0, px(6), 0, px(6)) })
            addView(mapStyleBtn, LinearLayout.LayoutParams(px(46), px(46)).apply { setMargins(0, px(6), 0, px(6)) })
            addView(mapTiltBtn, LinearLayout.LayoutParams(px(46), px(46)).apply { setMargins(0, px(6), 0, px(6)) })
            addView(mapRotateBtn, LinearLayout.LayoutParams(px(46), px(46)).apply { setMargins(0, px(6), 0, px(6)) })
            addView(offlineSaveBtn, LinearLayout.LayoutParams(px(46), px(46)).apply { setMargins(0, px(6), 0, px(6)) })
        }
        frame.addView(
            rightCol,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = px(12)
            }
        )

        // ----- Inferior: GPS (esquerda), cancelar rota (centro, só com rota), voltar ao painel (direita) -----
        // Botao centralizador mais moderno, com icone vetorial e contorno colorido.
        val gpsBtn = modernRecenterButton()
        cancelBtn = floatBtn("✕") { clearRoute() }
        val backBtn = floatBtn("⌂") { setScreen(0) }
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(gpsBtn, LinearLayout.LayoutParams(px(62), px(62)))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(cancelBtn, LinearLayout.LayoutParams(px(50), px(50)))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(backBtn, LinearLayout.LayoutParams(px(50), px(50)))
        }
        val attrib = label(9f, cLabel, align = Gravity.CENTER).apply {
            text = "Mapa © OpenStreetMap / OpenFreeMap · Rota OSRM · Alertas: Pardal (ODbL)"
        }
        val bottomCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), 0, px(12), px(12))
            addView(attrib, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = px(4) })
            addView(controlsRow, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        frame.addView(bottomCol, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // Sobreposto ao mapa, acompanha a posicao da moto em vez de ficar preso
        // a um canto. Fundo translucido e contraste para leitura ao pilotar.
        fullEtaCard = RouteEtaCard(this, accent)
        frame.addView(fullEtaCard, FrameLayout.LayoutParams(px(238), WRAP,
            Gravity.TOP or Gravity.START))
        fullEtaCard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            positionFullEtaCard()
        }

        refreshFavChips()
        refreshMapButtons()
        return frame
    }
}
