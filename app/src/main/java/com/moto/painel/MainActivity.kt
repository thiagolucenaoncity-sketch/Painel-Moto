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
    private var units = 0
    private var appliedVer = -1

    // Mapas (miniatura no painel + mapa completo) e seus overlays de localização
    private val maps = ArrayList<MapView>()
    private val myOverlays = ArrayList<MyLocationNewOverlay>()
    private var showMap = false
    private var locationStarted = false

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
    private var routePoints: List<GeoPoint> = emptyList()
    private var routeDest: GeoPoint? = null
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
            onFix(loc)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) = setGps("● GPS desligado", RED)

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        OsmConfiguration.getInstance().apply {
            load(this@MainActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("pt", "BR"))
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

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

    override fun onResume() {
        super.onResume()
        // Se mudou alguma configuração na tela de Configurações, redesenha a tela principal
        if (Prefs.version(this) != appliedVer) {
            setContentView(buildUi())
            updateMedia()
        }
        maps.forEach { it.onResume() }
        handler.post(tick)
        handler.post(pollSessions)
        handler.post(eqTick)
    }

    override fun onPause() {
        super.onPause()
        maps.forEach { it.onPause() }
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        locationManager.removeUpdates(locationListener)
        controller?.unregisterCallback(mediaCallback)
        tts?.shutdown()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!locationStarted) {
            locationStarted = true
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
        }
        enableOverlays()
        setGps("● GPS: aguardando sinal", GRAY)
    }

    @SuppressLint("MissingPermission")
    private fun enableOverlays() {
        myOverlays.forEach {
            it.enableMyLocation()
            it.enableFollowLocation()
        }
    }

    private fun setGps(text: String, color: Int) {
        gpsView.text = text
        gpsView.setTextColor(color)
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
    }

    private fun newMap(): MapView {
        val m = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }
        val my = MyLocationNewOverlay(GpsMyLocationProvider(this), m)
        val icon = markerBitmap(Prefs.getInt(this, "marker"), accent)
        my.setPersonIcon(icon)
        my.setDirectionIcon(icon)
        m.overlays.add(my)
        myOverlays.add(my)
        maps.add(m)
        drawRoute(m)
        return m
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
                val points = ArrayList<GeoPoint>()
                for (i in 0 until coords.length()) {
                    val c = coords.getJSONArray(i)
                    points.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
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
                    showRoute(points, list, GeoPoint(destLat, destLon), name, total, dur)
                }
            } catch (e: Exception) {
                handler.post { toast("Não consegui calcular a rota. Verifique a internet.") }
            }
        }.start()
    }

    private fun showRoute(
        points: List<GeoPoint>,
        list: List<Step>,
        dest: GeoPoint,
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

        maps.forEach {
            clearRouteFrom(it)
            drawRoute(it)
        }
        fullMap.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, 100)

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
        maps.forEach {
            clearRouteFrom(it)
            it.invalidate()
        }
        mapInstructionView.text = "Digite um destino acima"
        mapInfoView.text = ""
        navView.text = ""
        navView.visibility = View.GONE
    }

    private fun drawRoute(m: MapView) {
        val dest = routeDest ?: return
        if (routePoints.isEmpty()) return
        val line = Polyline().apply {
            setPoints(routePoints)
            outlinePaint.color = accent
            outlinePaint.strokeWidth = 10f
        }
        val marker = Marker(m).apply {
            position = dest
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = routeName
        }
        m.overlays.add(line)
        m.overlays.add(marker)
        m.invalidate()
    }

    private fun clearRouteFrom(m: MapView) {
        m.overlays.removeAll { it is Polyline || it is Marker }
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

    private fun statCard(title: String, value: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = box(context, CARD_BG, 14, BORDER)
        setPadding(px(6), px(8), px(6), px(8))
        addView(label(11f, GRAY, align = Gravity.CENTER).apply { text = title })
        addView(value, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun buildUi(): FrameLayout {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // Lê as escolhas salvas na tela de Configurações
        accent = Prefs.accentColor(this)
        units = Prefs.getInt(this, "units")
        appliedVer = Prefs.version(this)
        val bgStyle = Prefs.getInt(this, "bg")
        maps.clear()
        myOverlays.clear()

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

        setScreen(showMap)
        if (locationStarted) enableOverlays()
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
        minimap = newMap()
        minimap.alpha = 0.35f
        arcView = SpeedArcView(this).apply { this.accent = this@MainActivity.accent }
        arcView.visibility = if (gaugeStyle == 1) View.GONE else View.VISIBLE
        val digital = gaugeStyle == 1
        speedView = label(
            if (digital) (if (landscape) 110f else 120f) else (if (landscape) 64f else 84f),
            Color.WHITE,
            bold = true,
            align = Gravity.CENTER
        ).apply { text = "0" }
        unitView = label(16f, GRAY, align = Gravity.CENTER).apply { text = unitSpeed() }
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

        // Linha de navegação (aparece quando há rota)
        navView = label(15f, BLUE_LIGHT, bold = true, align = Gravity.CENTER).apply {
            visibility = View.GONE
        }

        // Cartões de estatística
        distView = label(20f, Color.WHITE, bold = true, mono = true, align = Gravity.CENTER).apply { text = distText(0.0) }
        timeView = label(20f, Color.WHITE, bold = true, mono = true, align = Gravity.CENTER).apply { text = "00:00:00" }
        maxView = label(20f, Color.WHITE, bold = true, mono = true, align = Gravity.CENTER).apply { text = "0" }
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

        // Card da música
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box(context, CARD_BG, 18, BORDER)
            setPadding(px(12), px(12), px(12), px(12))
        }
        artView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = box(context, BTN_BG, 12)
            clipToOutline = true
        }
        titleView = label(17f, Color.WHITE, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        artistView = label(13f, GRAY).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleView.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), 0, px(8), 0)
            addView(titleView, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(artistView, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        eqView = EqualizerView(this).apply { tint = accent }
        card.addView(artView, LinearLayout.LayoutParams(px(64), px(64)))
        card.addView(textCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(eqView, LinearLayout.LayoutParams(px(44), px(36)))

        // Controles de música
        val prevBtn = pill("⏮", BTN_BG, Color.WHITE, 22f)
        prevBtn.setOnClickListener { control { it.skipToPrevious() } }
        playBtn = pill("▶", accent, Color.BLACK, 28f)
        playBtn.setOnClickListener {
            control {
                if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) it.pause() else it.play()
            }
        }
        val nextBtn = pill("⏭", BTN_BG, Color.WHITE, 22f)
        nextBtn.setOnClickListener { control { it.skipToNext() } }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(prevBtn, LinearLayout.LayoutParams(0, px(64), 1f).apply { setMargins(px(4), 0, px(4), 0) })
        controls.addView(playBtn, LinearLayout.LayoutParams(0, px(64), 1.6f).apply { setMargins(px(4), 0, px(4), 0) })
        controls.addView(nextBtn, LinearLayout.LayoutParams(0, px(64), 1f).apply { setMargins(px(4), 0, px(4), 0) })

        // Botões de navegação entre telas
        val mapBtn = pill("🗺  Abrir mapa", BLUE_DARK, Color.WHITE, 15f)
        mapBtn.setOnClickListener { setScreen(true) }
        val settingsBtn = pill("⚙  Configurações", BTN_BG, Color.WHITE, 15f)
        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navRow.addView(mapBtn, LinearLayout.LayoutParams(0, px(48), 1f).apply { setMargins(px(4), 0, px(4), 0) })
        navRow.addView(settingsBtn, LinearLayout.LayoutParams(0, px(48), 1f).apply { setMargins(px(4), 0, px(4), 0) })

        fun gap(dp: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(dp) }

        if (landscape) {
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
            left.addView(vehicleView, gap(0))
            left.addView(arcBlock, LinearLayout.LayoutParams(MATCH, 0, 1f))

            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            right.addView(navView, gap(0))
            right.addView(statsRow, gap(4))
            right.addView(tripRow, gap(8))
            right.addView(card, gap(10))
            right.addView(controls, gap(10))
            right.addView(navRow, gap(10))

            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(right, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            root.addView(left, LinearLayout.LayoutParams(0, MATCH, 1.1f))
            root.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = px(12) })
        } else {
            root.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
            root.addView(vehicleView, gap(0))
            root.addView(arcBlock, LinearLayout.LayoutParams(MATCH, 0, 1.4f))
            root.addView(navView, gap(4))
            root.addView(statsRow, gap(8))
            root.addView(tripRow, gap(8))
            root.addView(card, gap(12))
            root.addView(controls, gap(12))
            root.addView(navRow, gap(10))
        }
        return root
    }

    private fun buildMapScreen(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF06142B.toInt())
        }

        // Barra de busca
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
        val backBtn = Button(this).apply {
            text = "Painel"
            setOnClickListener { setScreen(false) }
        }
        topRow.addView(searchInput, LinearLayout.LayoutParams(0, WRAP, 1f))
        topRow.addView(goBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        topRow.addView(backBtn, LinearLayout.LayoutParams(WRAP, WRAP))

        // Mapa completo
        fullMap = newMap()

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
        val attrib = label(10f, GRAY).apply { text = "Mapa © OpenStreetMap · Rota OSRM" }

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
