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
class RoadAlerts(
    private val context: Context,
    private val announce: (String) -> Unit,
    private val redraw: () -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    data class Hazard(val id: String, val lat: Double, val lon: Double, val type: String, val limit: String)
    private val handler = Handler(Looper.getMainLooper())
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
    private var hazards: List<Hazard> = emptyList()
    private var fetching = false
    private var lastFetch = 0L
    private var center: Location? = null
    private var attemptedCenter: Location? = null
    private var lastErrorMessage = 0L
    private var previous: Location? = null
    private val warned = mutableSetOf<String>()
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

    // O mapa pode ser explorado longe do GPS: consulta também a área aberta na tela.
    fun fetchAt(lat: Double, lon: Double) {
        if (!active || lat !in -85.0..85.0 || lon !in -180.0..180.0) return
        val loc = Location("map").apply { latitude = lat; longitude = lon }
        val now = System.currentTimeMillis()
        val previousCenter = attemptedCenter
        if (!fetching && (previousCenter == null || previousCenter.distanceTo(loc) > 2300f || now - lastFetch > 600000L) &&
            now - lastFetch > 25000L
        ) load(loc)
    }
    fun features(): FeatureCollection = FeatureCollection.fromFeatures(hazards.filter { enabled(it) }.map {
        Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply {
            addStringProperty("type", it.type)
            addStringProperty("priority", priorities[it.id] ?: "other")
        }
    })
    private fun enabled(h: Hazard): Boolean = Prefs.getInt(context, if (h.type == "radar") "alert_radar" else "alert_bump") == 0

    fun onLocation(loc: Location) {
        if (!active) return
        val now = System.currentTimeMillis()
        userPosition = Location(loc)
        if (activeRoute.size > 1 && hazards.isNotEmpty()) updatePriorities()
        // A consulta não depende da precisão: apenas os avisos sonoros precisam de GPS preciso.
        fetchAt(loc.latitude, loc.longitude)
        if (!loc.hasAccuracy() || loc.accuracy > 65f) return
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
        attemptedCenter = Location(loc)
        val lat = loc.latitude
        val lon = loc.longitude
        Thread {
            try {
                // Instâncias públicas Overpass podem falhar; a segunda é reserva.
                // Consulta dentro de 5 km, abrangendo a área do mapa.
                val query = """[out:json][timeout:25];(
                  node(around:5000,$lat,$lon)["highway"="speed_camera"];
                  node(around:5000,$lat,$lon)["enforcement"="maxspeed"];
                  node(around:5000,$lat,$lon)["traffic_calming"~"^(bump|hump|table|cushion|yes)$"];
                );out body;""".trimIndent()
                val servers = listOf(
                    "https://overpass.private.coffee/api/interpreter",
                    "https://overpass-api.de/api/interpreter"
                )
                var result: JSONObject? = null
                var failure: Exception? = null
                for (server in servers) {
                    try {
                        val url = URL("$server?data=" + URLEncoder.encode(query, "UTF-8"))
                        val conn = url.openConnection() as HttpURLConnection
                        try {
                            conn.connectTimeout = 10000
                            conn.readTimeout = 22000
                            conn.setRequestProperty("User-Agent", "RAVO-Android/1.0 (OpenStreetMap road alerts)")
                            if (conn.responseCode !in 200..299) {
                                throw java.io.IOException("Overpass HTTP ${conn.responseCode}")
                            }
                            val response = conn.inputStream.bufferedReader().use { it.readText() }
                            val json = JSONObject(response)
                            if (!json.has("elements")) throw java.io.IOException("Resposta sem elementos")
                            result = json
                        } finally {
                            conn.disconnect()
                        }
                        break
                    } catch (e: Exception) { failure = e }
                }
                val json = result ?: throw failure ?: java.io.IOException("Servidores indisponíveis")
                val arr = json.getJSONArray("elements")
                val found = LinkedHashMap<String, Hazard>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val tags = o.optJSONObject("tags") ?: continue
                    val type = if (tags.optString("highway") == "speed_camera" ||
                        tags.optString("enforcement") == "maxspeed") "radar" else "lombada"
                    val id = "osm:${o.optString("type", "node")}:${o.optString("id")}"
                    found[id] = Hazard(id, o.getDouble("lat"), o.getDouble("lon"), type,
                        tags.optString("maxspeed").filter { it.isDigit() })
                }
                handler.post {
                    if (!active) return@post
                    hazards = found.values.toList()
                    center = Location(loc)
                    warned.clear()
                    priorities = emptyMap()
                    updatePriorities()
                    redraw()
                    if (hazards.isEmpty()) onStatus("Nenhum radar ou lombada cadastrado no OpenStreetMap nesta área")
                }
            } catch (e: Exception) {
                handler.post {
                    if (!active) return@post
                    // Não apaga os pontos que já estavam no mapa em caso de falha de rede.
                    if (System.currentTimeMillis() - lastErrorMessage > 120000L) {
                        lastErrorMessage = System.currentTimeMillis()
                        onStatus("Não foi possível carregar radares/lombadas. Verifique a internet.")
                    }
                }
            } finally {
                handler.post { fetching = false }
            }
        }.start()
    }
}
