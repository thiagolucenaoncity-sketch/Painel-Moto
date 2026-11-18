package com.moto.painel

import android.content.Context
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Alertas auxiliares baseados em dados comunitários do OpenStreetMap.
 * A base não é completa nem garante sentido da via ou limite atualizado.
 */
class HazardAlerts(
    private val context: Context,
    private val onPointsChanged: (String, String) -> Unit,
    private val onSpokenAlert: (String) -> Unit
) {
    private data class Hazard(val id: String, val kind: String, val lat: Double, val lon: Double, val speed: String?)
    private val handler = Handler(Looper.getMainLooper())
    private var points = emptyList<Hazard>()
    private var lastQuery: Location? = null
    private var lastQueryAt = 0L
    private var fetching = false
    private var alive = true
    private var tone: ToneGenerator? = null
    private val announced = HashSet<String>()
    private var lastBeepAt = 0L

    private fun enabled(kind: String) =
        Prefs.getInt(context, if (kind == "radar") "alerts_radar" else "alerts_bump") == 1

    fun update(loc: Location, route: List<Pair<Double, Double>>) {
        if (!alive) return
        if (!enabled("radar") && !enabled("bump")) {
            onPointsChanged(EMPTY, EMPTY)
            return
        }
        val now = System.currentTimeMillis()
        val moved = lastQuery?.distanceTo(loc) ?: Float.MAX_VALUE
        if (!fetching && (lastQueryAt == 0L || (moved > 800f && now - lastQueryAt > 60000L) || now - lastQueryAt > 360000L)) {
            fetch(loc)
        }

        if (loc.accuracy > 55f || !loc.hasSpeed() || loc.speed < 2.2f) return
        val candidates = points.mapNotNull { h ->
            if (!enabled(h.kind)) return@mapNotNull null
            val l = Location("osm").apply { latitude = h.lat; longitude = h.lon }
            val d = loc.distanceTo(l)
            if (d > 400f || d < 8f) return@mapNotNull null
            // Com rota ativa, evita pontos muito afastados da trajetória desenhada.
            if (route.size > 1 && route.none { pair ->
                val routeLoc = Location("route").apply { latitude = pair.first; longitude = pair.second }
                routeLoc.distanceTo(l) < 65f
            }) return@mapNotNull null
            // Sem rota, exige direção coerente com o rumo GPS.
            if (loc.hasBearing()) {
                val difference = abs(((loc.bearing - loc.bearingTo(l) + 540f) % 360f) - 180f)
                if (difference > 50f) return@mapNotNull null
            }
            h to d
        }.sortedBy { it.second }
        val nearest = candidates.firstOrNull() ?: return
        val h = nearest.first
        val distance = nearest.second
        if (distance <= 300f && announced.add(h.id) && Prefs.getInt(context, "alerts_voice") == 1) {
            val name = if (h.kind == "radar") "Radar" else "Lombada"
            val limit = h.speed?.let { " de $it quilômetros por hora" } ?: ""
            onSpokenAlert("Atenção, $name$limit a aproximadamente ${distance.toInt()} metros")
        }
        if (Prefs.getInt(context, "alerts_beep") != 1 || distance > 160f) return
        val interval = when {
            distance < 35f -> 320L
            distance < 75f -> 650L
            else -> 1200L
        }
        if (now - lastBeepAt >= interval) {
            lastBeepAt = now
            try {
                if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 65)
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 110)
            } catch (_: Exception) {}
        }
    }

    private fun fetch(loc: Location) {
        fetching = true
        lastQueryAt = System.currentTimeMillis()
        lastQuery = Location(loc)
        val lat = loc.latitude
        val lon = loc.longitude
        Thread {
            // Consulta pequena (~2 km de raio), com intervalo e sem uso intensivo do serviço público.
            val query = "[out:json][timeout:15];(node[\"highway\"=\"speed_camera\"](around:2000,$lat,$lon);node[\"traffic_calming\"~\"^(bump|hump|table|cushion|rumble_strip)$\"](around:2000,$lat,$lon););out body;"
            val loaded = ArrayList<Hazard>()
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val conn = URL("https://overpass-api.de/api/interpreter?data=$encoded").openConnection() as HttpURLConnection
                conn.connectTimeout = 9000
                conn.readTimeout = 17000
                conn.setRequestProperty("User-Agent", "RAVO/1.1 (OSM hazard alerts)")
                try {
                    if (conn.responseCode == 200) {
                        val input = conn.inputStream.bufferedReader().use { it.readText() }
                        val arr = JSONObject(input).optJSONArray("elements")
                        if (arr != null) for (i in 0 until arr.length()) {
                            val node = arr.optJSONObject(i) ?: continue
                            val tags = node.optJSONObject("tags") ?: continue
                            val kind = if (tags.optString("highway") == "speed_camera") "radar" else "bump"
                            val id = "${kind}_${node.optLong("id")}"
                            loaded.add(Hazard(id, kind, node.optDouble("lat"), node.optDouble("lon"), tags.optString("maxspeed").takeIf { it.matches(Regex("\\d{1,3}")) }))
                        }
                    }
                } finally { conn.disconnect() }
            } catch (_: Exception) {
                // Se a rede falhar, mantém os últimos pontos conhecidos.
            }
            handler.post {
                fetching = false
                if (!alive) return@post
                if (loaded.isNotEmpty()) {
                    points = loaded
                    announced.retainAll(loaded.map { it.id }.toSet())
                    publish()
                }
            }
        }.start()
    }

    fun publish() {
        if (!alive) return
        onPointsChanged(geo("radar"), geo("bump"))
    }

    private fun geo(kind: String): String {
        val features = points.filter { it.kind == kind && enabled(kind) }.map { h ->
            "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[${h.lon},${h.lat}]},\"properties\":{\"id\":\"${h.id}\"}}"
        }
        return "{\"type\":\"FeatureCollection\",\"features\":[${features.joinToString(",")}]}"
    }

    fun close() {
        alive = false
        try { tone?.release() } catch (_: Exception) {}
        tone = null
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"
    }
}
