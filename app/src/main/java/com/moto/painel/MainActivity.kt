package com.moto.painel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
        rect.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(rect, 135f, 270f, false, trackPaint)

        val frac = (speed / 200f).coerceIn(0f, 1f)
        if (frac > 0f) {
            arcPaint.color = when {
                speed > 120f -> RED
                speed > 80f -> YELLOW
                else -> BLUE
            }
            canvas.drawArc(rect, 135f, 270f * frac, false, arcPaint)
        }
    }
}

// ---------- Equalizador da música ----------

class EqualizerView(ctx: Context) : View(ctx) {
    var playing: Boolean = false
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

// ---------- Tela principal ----------

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null

    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var gpsView: TextView
    private lateinit var speedView: TextView
    private lateinit var arcView: SpeedArcView
    private lateinit var distView: TextView
    private lateinit var timeView: TextView
    private lateinit var maxView: TextView
    private lateinit var tripBtn: Button
    private lateinit var artView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var eqView: EqualizerView
    private lateinit var playBtn: Button

    // Estado da viagem
    private var lastLoc: Location? = null
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

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val kmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
            val shown = if (kmh < 2f) 0 else kmh.roundToInt()

            speedView.text = shown.toString()
            arcView.speed = shown.toFloat()
            if (shown > maxSpeed) maxSpeed = shown
            maxView.text = maxSpeed.toString()

            setGps("● GPS ok (±${loc.accuracy.roundToInt()} m)", GREEN)

            if (tripRunning) {
                lastLoc?.let { prev ->
                    val d = prev.distanceTo(loc)
                    if (d in 3f..500f) totalM += d
                }
                distView.text = String.format(Locale.US, "%.2f km", totalM / 1000.0)
            }
            lastLoc = loc
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) = setGps("● GPS desligado", RED)

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startGps()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startGps()
        } else {
            setGps("● Sem permissão de localização", RED)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        handler.post(pollSessions)
        handler.post(eqTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        controller?.unregisterCallback(mediaCallback)
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
        setGps("● GPS: aguardando sinal", GRAY)
    }

    private fun setGps(text: String, color: Int) {
        gpsView.text = text
        gpsView.setTextColor(color)
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
        lastLoc = null
        tripBtn.text = "▶ Iniciar"
        distView.text = "0.00 km"
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

    // ---------- Interface ----------

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
        val frame = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF06142B.toInt(), 0xFF02060F.toInt())
            )
        }
        frame.addView(GridView(this), FrameLayout.LayoutParams(MATCH, MATCH))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(16))
        }
        frame.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))

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

        // Arco com a velocidade no centro
        arcView = SpeedArcView(this)
        speedView = label(84f, Color.WHITE, bold = true, align = Gravity.CENTER).apply { text = "0" }
        val unitView = label(16f, GRAY, align = Gravity.CENTER).apply { text = "km/h" }
        val centerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(speedView, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(unitView, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val arcBlock = FrameLayout(this).apply {
            addView(arcView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(centerCol, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        // Cartões de estatística
        distView = label(20f, Color.WHITE, bold = true, mono = true, align = Gravity.CENTER).apply { text = "0.00 km" }
        timeView = label(20f, Color.WHITE, bold = true, mono = true, align = Gravity.CENTER).apply { text = "00:00:00" }
        maxView = label(20f, Color.WHITE, bold = true, mono = true, align = Gravity.CENTER).apply { text = "0" }
        val statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statsRow.addView(statCard("VIAGEM", distView), LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(px(4), 0, px(4), 0) })
        statsRow.addView(statCard("TEMPO", timeView), LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(px(4), 0, px(4), 0) })
        statsRow.addView(statCard("MÁX.", maxView), LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(px(4), 0, px(4), 0) })

        // Botões da viagem
        tripBtn = pill("▶ Iniciar", BLUE_DARK, Color.WHITE, 15f)
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
        eqView = EqualizerView(this)
        card.addView(artView, LinearLayout.LayoutParams(px(64), px(64)))
        card.addView(textCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(eqView, LinearLayout.LayoutParams(px(44), px(36)))

        // Controles em formato de cápsula
        val prevBtn = pill("⏮", BTN_BG, Color.WHITE, 22f)
        prevBtn.setOnClickListener { control { it.skipToPrevious() } }
        playBtn = pill("▶", BLUE, Color.BLACK, 28f)
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

        // Montagem final
        root.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(arcBlock, LinearLayout.LayoutParams(MATCH, 0, 1.4f))
        root.addView(statsRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) })
        root.addView(tripRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) })
        root.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(12) })
        root.addView(controls, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(12) })
        return frame
    }
}
