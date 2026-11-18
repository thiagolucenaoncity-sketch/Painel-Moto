package com.moto.painel

import android.content.Context
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng
import kotlin.math.cos
import kotlin.math.sqrt
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Pontos colaborativos do OpenStreetMap via Overpass.
 * Não são dados em tempo real nem garantem cobertura completa.
 */
class RoadAlerts(private val context: Context, private val announce: (String) -> Unit, private val redraw: () -> Unit) {
    data class Hazard(val id: String, val lat: Double, val lon: Double, val type: String, val limit: String)
    private val handler = Handler(Looper.getMainLooper())
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
    private var hazards: List<Hazard> = emptyList()
    private var fetching = false
    private var lastFetch = 0L
    private var center: Location? = null
    private var previous: Location? = null
    private val warned = mutableSetOf<String>()
    private val beepTimes = mutableMapOf<String, Long>()
    private var lastBeep = 0L
    private var active = true
    private var activeRoute: List<LatLng> = emptyList()
    private var userPosition: Location? = null
    private var priorities: Map<String, String> = emptyMap()

    // Projeção local em metros: distância lateral e progresso sobre a rota.
    private fun project(lat: Double, lon: Double, path: List<LatLng>): Pair<Double, Double>? {
        if (path.size < 2) return null
        val meterLon = 111320.0 * cos(Math.toRadians(lat))
        val meterLat = 111320.0
        var best = Double.MAX_VALUE
        var progress = 0.0
        var traveled = 0.0
        for (i in 0 until path.lastIndex) {
            val a = path[i]
            val b = path[i + 1]
            val ax = (a.longitude - lon) * meterLon
            val ay = (a.latitude - lat) * meterLat
            val dx = (b.longitude - a.longitude) * meterLon
            val dy = (b.latitude - a.latitude) * meterLat
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0.01) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val distance = sqrt((ax + t * dx) * (ax + t * dx) + (ay + t * dy) * (ay + t * dy))
            val len = sqrt(len2)
            if (distance < best) {
                best = distance
                progress = traveled + t * len
            }
            traveled += len
        }
        return best to progress
    }

    fun setRoute(points: List<LatLng>) {
        activeRoute = points.toList()
        updatePriorities()
    }

    private fun updatePriorities() {
        val path = activeRoute
        val me = userPosition
        val currentProgress = if (me != null && path.size > 1) project(me.latitude, me.longitude, path)?.second else null
        val next = if (currentProgress != null) hazards.filter { enabled(it) }.mapNotNull { h ->
            val match = project(h.lat, h.lon, path) ?: return@mapNotNull null
            // Somente pontos próximos ao traçado e à frente, evitando retorno pela mesma via.
            if (match.first > 28.0 || match.second < currentProgress + 8.0) null
            else h.id to (match.second - currentProgress)
        }.minByOrNull { it.second }?.first else null
        val updated = hazards.associate { h ->
            val nearRoute = path.size > 1 && (project(h.lat, h.lon, path)?.first ?: Double.MAX_VALUE) <= 28.0
            h.id to when {
                h.id == next -> "next"
                nearRoute -> "route"
                else -> "other"
            }
        }
        if (updated != priorities) {
            priorities = updated
            redraw()
        }
    }

    fun stop() { active = false; tone.release(); handler.removeCallbacksAndMessages(null) }
    fun features(): FeatureCollection = FeatureCollection.fromFeatures(hazards.filter { enabled(it) }.map {
        Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply {
            addStringProperty("type", it.type)
            addStringProperty("priority", priorities[it.id] ?: "other")
        }
    })
    private fun enabled(h: Hazard): Boolean = Prefs.getInt(context, if (h.type == "radar") "alert_radar" else "alert_bump") == 0

    fun onLocation(loc: Location) {
        if (!active || !loc.hasAccuracy() || loc.accuracy > 65f) return
        val now = System.currentTimeMillis()
        userPosition = Location(loc)
        if (activeRoute.size > 1 && hazards.isNotEmpty()) updatePriorities()
        val origin = center
        if (!fetching && (origin == null || (origin.distanceTo(loc) > 1800 && now - lastFetch > 120000) || now - lastFetch > 600000)) {
            load(loc)
        }
        val prev = previous
        previous = Location(loc)
        if (loc.hasSpeed() && loc.speed < 1.5f) return
        val candidates = hazards.filter { enabled(it) }.map { h ->
            val p = Location("osm").apply { latitude = h.lat; longitude = h.lon }
            h to loc.distanceTo(p).toDouble()
        }.filter { (h, d) ->
            if (d > 450 || d < 5) return@filter false
            if (prev != null) {
                val p = Location("osm").apply { latitude = h.lat; longitude = h.lon }
                if (prev.distanceTo(p) < d - 8) return@filter false
            }
            if (loc.hasBearing() && loc.speed > 2f) {
                val p = Location("osm").apply { latitude = h.lat; longitude = h.lon }
                val angle = abs(((loc.bearing - loc.bearingTo(p) + 540f) % 360f) - 180f)
                if (angle > 65f) return@filter false
            }
            true
        }.sortedBy { it.second }
        val closest = candidates.firstOrNull() ?: return
        val (h, d) = closest
        if (d < 350 && warned.add(h.id)) {
            val kind = if (h.type == "radar") "radar" else "lombada"
            announce("Atenção, $kind a aproximadamente ${d.toInt()} metros" + if (h.limit.isNotEmpty()) ", limite de ${h.limit} quilômetros por hora" else "")
        }
        // Bipes mais rápidos à medida que a distância diminui, como sensor de estacionamento.
        if (Prefs.getInt(context, "alert_beep") == 0 && d < 150) {
            val gap = when {
                d < 25 -> 300L
                d < 55 -> 550L
                d < 90 -> 950L
                else -> 1500L
            }
            if (now - lastBeep > gap) {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                lastBeep = now
            }
        }
    }

    private fun load(loc: Location) {
        fetching = true
        lastFetch = System.currentTimeMillis()
        val lat = loc.latitude
        val lon = loc.longitude
        Thread {
            try {
                val q = "[out:json][timeout:15];(node(around:3500,$lat,$lon)[highway=speed_camera];node(around:3500,$lat,$lon)[traffic_calming~\"^(bump|hump|table|cushion)$\"];);out body;"
                val url = URL("https://overpass.kumi.systems/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"))
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", "RAVO-Navigation/1.0 (OSM road alerts)")
                val json = conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                conn.disconnect()
                val arr = json.getJSONArray("elements")
                val found = ArrayList<Hazard>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val tags = o.optJSONObject("tags") ?: continue
                    val kind = if (tags.optString("highway") == "speed_camera") "radar" else "lombada"
                    found.add(Hazard(o.optString("id"), o.getDouble("lat"), o.getDouble("lon"), kind, tags.optString("maxspeed").filter { it.isDigit() }))
                }
                handler.post {
                    if (!active) return@post
                    hazards = found
                    center = Location(loc)
                    warned.clear()
                    updatePriorities()
                    redraw()
                }
            } catch (_: Exception) {
                // Falha de rede não deve interromper o GPS; tenta novamente depois.
            } finally {
                handler.post { fetching = false }
            }
        }.start()
    }
}
