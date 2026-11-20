package com.moto.painel.maps

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
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
        val back = makeTab("← Voltar ao RAVO") { finish() }
        root.addView(back, LinearLayout.LayoutParams(-1, dp(46)))
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
            content.addView(RavoMapsCoreView(this), FrameLayout.LayoutParams(-1, -1))
            return
        }
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
        mapView?.onDestroy()
        mapView = null
        super.onDestroy()
    }
}
