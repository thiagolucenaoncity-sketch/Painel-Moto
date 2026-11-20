package com.moto.painel.maps

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import kotlin.math.cos
import kotlin.math.sin
import org.maplibre.android.maps.MapLibreMap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.moto.painel.Diagnostics
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Dois experimentos isolados do painel de navegação.
 * OPEN: renderização MapLibre, usa tiles online.
 * CORE: desenho em Canvas, geometria FICTÍCIA sem GPS.
 *
 * Nenhum deles substitui o mapa do RAVO Original.
 */
class RavoMapsLabActivity : Activity() {
    private enum class Engine { CORE, OPEN }
    private var selected = Engine.CORE
    private var mapView: MapView? = null
    private var openMap: MapLibreMap? = null
    private var coreMap: RavoMapsCoreView? = null
    private lateinit var stressButton: Button
    private lateinit var stressStatus: TextView
    private var stressRunning = false
    private var stressStarted = 0L
    private var lastFrameNs = 0L
    private var frames = 0
    private var slowFrames = 0
    private val frameTimes = ArrayList<Float>()
    private val stressHandler = Handler(Looper.getMainLooper())
    private val frameClock = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!stressRunning) return
            if (lastFrameNs != 0L) {
                val durationMs = (frameTimeNanos - lastFrameNs) / 1_000_000f
                if (durationMs > 0f && durationMs < 2000f) {
                    frameTimes.add(durationMs)
                    frames++
                    if (durationMs > 34f) slowFrames++
                }
            }
            lastFrameNs = frameTimeNanos
            val phase = (SystemClock.elapsedRealtime() - stressStarted) / 1000f
            if (selected == Engine.CORE) coreMap?.benchmarkFrame(phase * 1.4f)
            frameClock.postFrameCallback(this)
        }
    }
    private val stressTicker = object : Runnable {
        override fun run() {
            if (!stressRunning) return
            val elapsed = SystemClock.elapsedRealtime() - stressStarted
            if (elapsed >= 30_000L) {
                stopStress(completed = true)
                return
            }
            if (selected == Engine.OPEN) {
                // Movimento artificial de câmera, sem rota/GPS e sem requisições extras manuais.
                val t = elapsed / 1000.0
                openMap?.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(-23.5505 + sin(t * 0.3) * 0.003,
                            -46.6333 + cos(t * 0.3) * 0.003))
                        .zoom(12.5 + sin(t * 0.4) * 0.45)
                        .build()
                ))
            }
            val secs = elapsed / 1000
            stressStatus.text = "Teste em andamento: ${secs}s / 30s • ${frames} quadros medidos"
            if (secs > 0 && secs % 10 == 0L) {
                // Coleta própria do teste, com taxa reduzida e dados locais.
                if (secs != lastMemorySecond) {
                    lastMemorySecond = secs
                    Diagnostics.memory(this@RavoMapsLabActivity, selected.name.lowercase(), 0L)
                }
            }
            stressHandler.postDelayed(this, 200L)
        }
    }
    private var lastMemorySecond = -1L
    private var started = false
    private var resumed = false
    private var diagnosticsSession: Diagnostics.Session? = null
    private lateinit var content: FrameLayout
    private lateinit var coreTab: Button
    private lateinit var openTab: Button

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(6, 16, 31))
        }
        val heading = TextView(this).apply {
            text = "RAVO MAPS LAB  •  TESTES"
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(heading, LinearLayout.LayoutParams(-1, dp(52)))
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        coreTab = makeTab("CORE · próprio") { show(Engine.CORE) }
        openTab = makeTab("OPEN · MapLibre") { show(Engine.OPEN) }
        tabs.addView(coreTab, LinearLayout.LayoutParams(0, dp(50), 1f))
        tabs.addView(openTab, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(50)))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        stressStatus = TextView(this).apply {
            text = "Teste local de 30s. Com o aparelho parado."
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        root.addView(stressStatus, LinearLayout.LayoutParams(-1, dp(33)))
        stressButton = makeTab("▶ Iniciar teste de estresse (30s)") {
            if (stressRunning) stopStress(completed = false)
            else if (!Diagnostics.enabled(this)) {
                stressStatus.text = "Ative o diagnóstico nas Configurações antes de testar."
            } else {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Teste de estresse do RAVO")
                    .setMessage("Execute somente com o aparelho parado. O mapa terá movimento artificial por 30 segundos, sem navegação real. Continuar?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Iniciar") { _, _ -> startStress() }
                    .show()
            }
        }
        root.addView(stressButton, LinearLayout.LayoutParams(-1, dp(46)))
        val back = makeTab("← Voltar ao RAVO") { finish() }
        root.addView(back, LinearLayout.LayoutParams(-1, dp(42)))
        setContentView(root)

        selected = if (savedInstanceState?.getString("engine") == "OPEN") Engine.OPEN else Engine.CORE
        show(selected)
    }

    private fun makeTab(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.WHITE)
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { action() }
    }

    private fun show(engine: Engine) {
        if (stressRunning) stopStress(completed = false)
        if (selected != engine || diagnosticsSession == null) {
            diagnosticsSession?.stop()
            diagnosticsSession = Diagnostics.Session(this, if (engine == Engine.CORE) "core" else "open")
            if (resumed) diagnosticsSession?.start()
        }
        selected = engine
        coreTab.setBackgroundColor(if (engine == Engine.CORE) 0xFFB05A19.toInt() else 0xFF19324C.toInt())
        openTab.setBackgroundColor(if (engine == Engine.OPEN) 0xFFB05A19.toInt() else 0xFF19324C.toInt())
        content.removeAllViews()

        if (engine == Engine.CORE) {
            coreMap = RavoMapsCoreView(this)
            content.addView(coreMap, FrameLayout.LayoutParams(-1, -1))
            return
        }
        coreMap = null
        val map = ensureOpenMap()
        (map.parent as? ViewGroup)?.removeView(map)
        content.addView(map, FrameLayout.LayoutParams(-1, -1))
        val note = TextView(this).apply {
            text = "OPEN: mapa online de São Paulo (não é offline)"
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(0xE60D2236.toInt())
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        content.addView(note, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
    }

    private fun ensureOpenMap(): MapView {
        mapView?.let { return it }
        return MapView(this).also { view ->
            mapView = view
            view.onCreate(null)
            if (started) view.onStart()
            if (resumed) view.onResume()
            view.getMapAsync { map ->
                openMap = map
                map.setStyle(Style.Builder().fromUri(
                    "https://tiles.openfreemap.org/styles/liberty"
                ))
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(-23.5505, -46.6333))
                    .zoom(12.0)
                    .build()
            }
        }
    }

    private fun startStress() {
        if (stressRunning) return
        if (selected == Engine.OPEN && openMap == null) {
            stressStatus.text = "Aguarde o mapa Open carregar antes do teste."
            return
        }
        stressRunning = true
        stressStarted = SystemClock.elapsedRealtime()
        lastFrameNs = 0L
        frames = 0
        slowFrames = 0
        lastMemorySecond = -1L
        frameTimes.clear()
        stressButton.text = "■ Interromper teste"
        Diagnostics.event(this, "stress_start", mapOf("engine" to selected.name.lowercase(), "seconds" to 30))
        frameClock.postFrameCallback(frameCallback)
        stressHandler.post(stressTicker)
    }

    private fun stopStress(completed: Boolean) {
        if (!stressRunning) return
        stressRunning = false
        stressHandler.removeCallbacks(stressTicker)
        frameClock.removeFrameCallback(frameCallback)
        stressButton.text = "▶ Iniciar teste de estresse (30s)"
        val elapsed = (SystemClock.elapsedRealtime() - stressStarted).coerceAtLeast(1L)
        val sorted = frameTimes.sorted()
        val p95 = if (sorted.isEmpty()) 0f else sorted[((sorted.size - 1) * 0.95).toInt()]
        val fps = frames * 1000.0 / elapsed
        val fields = mapOf(
            "engine" to selected.name.lowercase(),
            "completed" to completed,
            "elapsed_ms" to elapsed,
            "approx_fps" to String.format(java.util.Locale.US, "%.1f", fps),
            "p95_frame_ms" to String.format(java.util.Locale.US, "%.1f", p95),
            "frames_over_34ms" to slowFrames
        )
        Diagnostics.event(this, "stress_result", fields)
        Diagnostics.memory(this, selected.name.lowercase(), 0L)
        stressStatus.text = "Fim: ${String.format(java.util.Locale.US, "%.1f", fps)} FPS aprox. • P95 ${String.format(java.util.Locale.US, "%.1f", p95)} ms • ${slowFrames} quadros lentos"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("engine", selected.name)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        started = true
        mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        diagnosticsSession?.start()
        mapView?.onResume()
    }

    override fun onPause() {
        if (stressRunning) stopStress(completed = false)
        diagnosticsSession?.stop()
        resumed = false
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        started = false
        mapView?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onDestroy() {
        if (stressRunning) stopStress(completed = false)
        stressHandler.removeCallbacksAndMessages(null)
        frameClock.removeFrameCallback(frameCallback)
        mapView?.onDestroy()
        mapView = null
        super.onDestroy()
    }
}
