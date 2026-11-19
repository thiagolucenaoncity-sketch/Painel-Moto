package com.moto.painel

import android.content.Context
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.abs

/**
 * Radares e lombadas de SP: base Pardal embarcada, atualizável a cada 15 dias.
 * Semáforos: cache separado. São dados auxiliares, nunca em tempo real.
 */
class RoadAlerts(
    private val context: Context,
    private val announce: (String) -> Unit,
    private val redraw: () -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    data class Hazard(val id: String, val lat: Double, val lon: Double, val type: String, val limit: String, val direction: Float? = null)
    private val handler = Handler(Looper.getMainLooper())
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
    private var hazards: List<Hazard> = emptyList()
    private var fetching = false
    private var databaseReloadPending = false
    private var lastFetch = 0L
    private var center: Location? = null
    private var attemptedCenter: Location? = null
    private var lastErrorMessage = 0L
    private var lastFailure = false
    @Volatile private var bundled: List<Hazard>? = null
    private var previous: Location? = null
    private val warned = mutableSetOf<String>()
    private var lastBeep = 0L
    private var active = true
    private var activeRoute: List<LatLng> = emptyList()
    private var userPosition: Location? = null
    private var priorities: Map<String, String> = emptyMap()
    private var routeProgressByHazard: Map<String, Double> = emptyMap()
    private var classificationRevision = 0
    private var lastUserProgressAt = 0L
    private var userRouteProgress = 0.0
    private var lastProgressPosition: Location? = null
    private val trafficSignalsLazy = lazy {
        TrafficSignalSource(context) { refreshTrafficSignals() }
    }
    private val trafficSignals: TrafficSignalSource get() = trafficSignalsLazy.value
    private val lazyTrafficReady: Boolean get() = trafficSignalsLazy.isInitialized()

    private fun signalHazards(lat: Double, lon: Double): List<Hazard> =
        trafficSignals.nearby(lat, lon).map { s ->
            Hazard(s.id, s.lat, s.lon, "semaforo", "")
        }

    private fun refreshTrafficSignals() {
        val c = center ?: return
        hazards = hazards.filter { it.type != "semaforo" } + signalHazards(c.latitude, c.longitude)
        classifyRouteInBackground()
        redraw()
    }

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
        lastUserProgressAt = 0L
        classifyRouteInBackground()
    }

    /**
     * A projeção de CADA alerta na rota é custosa (N pontos x M trechos).
     * Calcula apenas quando muda a rota ou a lista de alertas, fora da UI.
     * A cada GPS, consulta somente o progresso já calculado.
     */
    private fun classifyRouteInBackground() {
        val revision = ++classificationRevision
        val route = activeRoute.toList()
        val snapshot = hazards.toList()
        routeProgressByHazard = emptyMap()
        if (route.size < 2 || snapshot.isEmpty()) {
            updatePriorities()
            return
        }
        Thread {
            val classified = HashMap<String, Double>()
            for (hazard in snapshot) {
                val match = project(hazard.lat, hazard.lon, route) ?: continue
                if (match.first <= 28.0) classified[hazard.id] = match.second
            }
            handler.post {
                if (!active || revision != classificationRevision) return@post
                routeProgressByHazard = classified
                lastUserProgressAt = 0L
                updatePriorities()
            }
        }.start()
    }

    private fun updatePriorities() {
        val now = System.currentTimeMillis()
        val me = userPosition
        if (activeRoute.size > 1 && me != null &&
            (lastUserProgressAt == 0L || now - lastUserProgressAt > 2600L ||
             (lastProgressPosition?.distanceTo(me) ?: Float.MAX_VALUE) > 18f)
        ) {
            userRouteProgress = project(me.latitude, me.longitude, activeRoute)?.second ?: 0.0
            lastUserProgressAt = now
            lastProgressPosition = Location(me)
        }
        // Não é necessário recalcular a geometria da rota para cada ponto a cada segundo.
        val eligibleIds = hazards.asSequence()
            .filter { it.type != "semaforo" && enabled(it) }
            .map { it.id }.toHashSet()
        val nextId = if (activeRoute.size > 1 && me != null) {
            routeProgressByHazard.entries.asSequence()
                .filter { (id, progress) ->
                    progress > userRouteProgress + 8.0 && id in eligibleIds
                }
                .minByOrNull { it.value }?.key
        } else null
        val updated = hazards.associate { hazard ->
            hazard.id to when {
                hazard.id == nextId -> "next"
                routeProgressByHazard.containsKey(hazard.id) -> "route"
                else -> "other"
            }
        }
        if (updated != priorities) {
            priorities = updated
            redraw()
        }
    }

    fun stop() {
        active = false
        if (lazyTrafficReady) trafficSignals.close()
        tone.release()
        handler.removeCallbacksAndMessages(null)
    }

    // O mapa pode ser explorado longe do GPS: consulta também a área aberta na tela.
    fun fetchAt(lat: Double, lon: Double) {
        if (!active || lat !in -85.0..85.0 || lon !in -180.0..180.0) return
        // Semáforos têm cache/retry independente da base fixa de radares.
        // A ausência de download não deve depender do intervalo de recarga dos radares.
        if (Prefs.getInt(context, "alert_semaforo") == 0) trafficSignals.fetchAt(lat, lon)
        val loc = Location("map").apply { latitude = lat; longitude = lon }
        val now = System.currentTimeMillis()
        val previousCenter = attemptedCenter
        val retryDelay = if (lastFailure) 90000L else 600000L
        if (!fetching && (previousCenter == null || previousCenter.distanceTo(loc) > 2300f || now - lastFetch > retryDelay) &&
            now - lastFetch > (if (lastFailure) 90000L else 25000L)
        ) load(loc)
    }
    fun features(): FeatureCollection = FeatureCollection.fromFeatures(hazards.filter { enabled(it) }.map {
        Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply {
            addStringProperty("type", it.type)
            addStringProperty("priority", priorities[it.id] ?: "other")
            // A placa ao lado do radar só aparece quando a base conhece o limite.
            // Sem valor cadastrado, não inventa velocidade.
            val knownLimit = if (it.type == "radar") it.limit.toIntOrNull() else null
            if (knownLimit != null && knownLimit in 10..140 && knownLimit % 5 == 0) {
                addStringProperty("speedSign", "ravo-limit-" + knownLimit)
            }
        }
    })
    private fun enabled(h: Hazard): Boolean = Prefs.getInt(context, when (h.type) {
        "radar" -> "alert_radar"
        "semaforo" -> "alert_semaforo"
        else -> "alert_bump"
    }) == 0

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
        val latWindow = 470.0 / 111320.0
        val lonWindow = latWindow / cos(Math.toRadians(loc.latitude)).coerceAtLeast(0.2)
        val candidates = hazards.asSequence()
            .filter { h ->
                enabled(h) && h.type != "semaforo" &&
                    abs(h.lat - loc.latitude) <= latWindow &&
                    abs(h.lon - loc.longitude) <= lonWindow
            }.map { h ->
                val p = Location("road-hazard").apply { latitude = h.lat; longitude = h.lon }
                h to loc.distanceTo(p).toDouble()
            }.filter { (h, d) ->
            if (d > 450 || d < 5) return@filter false
            if (prev != null) {
                val p = Location("osm").apply { latitude = h.lat; longitude = h.lon }
                if (prev.distanceTo(p) < d - 8) return@filter false
            }
            if (loc.hasBearing() && loc.speed > 2f) {
                val p = Location("database").apply { latitude = h.lat; longitude = h.lon }
                val angle = abs(((loc.bearing - loc.bearingTo(p) + 540f) % 360f) - 180f)
                if (angle > 65f) return@filter false
                // Quando a base conhece o sentido fiscalizado, evita apitar na pista oposta.
                if (h.direction != null) {
                    val wrongDirection = abs(((loc.bearing - h.direction + 540f) % 360f) - 180f)
                    if (wrongDirection > 60f) return@filter false
                }
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

    /** Uma atualização concluída substitui os dados locais sem reiniciar o mapa ou GPS. */
    fun databaseUpdated() {
        bundled = null
        val position = center ?: attemptedCenter ?: return
        if (fetching) databaseReloadPending = true
        else load(Location(position))
    }

    private fun readBundled(): List<Hazard> {
        bundled?.let { return it }
        val points = ArrayList<Hazard>()
        // Consolida apenas radares FIXED compatíveis. A média já é usada
        // tanto para o ícone quanto para voz/bipes, sem duplicar alertas.
        // SECTION/RED_LIGHT e lombadas continuam com os pontos originais.
        val rawRadars = ArrayList<RadarDeduplicator.Record>()
        RadarDatabaseUpdater.reader(context, "sp_radares.csv").useLines { lines ->
            lines.drop(1).forEachIndexed { i, line ->
                val columns = line.split(',')
                if (columns.size < 9 || columns[5] != "1") return@forEachIndexed
                val lat = columns[0].toDoubleOrNull() ?: return@forEachIndexed
                val lon = columns[1].toDoubleOrNull() ?: return@forEachIndexed
                rawRadars.add(RadarDeduplicator.Record(
                    id = "pardal:radar:$i",
                    lat = lat, lon = lon, kind = columns[2],
                    limit = columns[3].filter { it.isDigit() },
                    direction = columns[8].toFloatOrNull(),
                    source = columns[4]
                ))
            }
        }
        RadarDeduplicator.consolidate(rawRadars).forEach { radar ->
            points.add(Hazard(radar.id, radar.lat, radar.lon, "radar",
                radar.limit, radar.direction))
        }
        RadarDatabaseUpdater.reader(context, "sp_lombadas.csv").useLines { lines ->
            lines.drop(1).forEachIndexed { i, line ->
                val c = line.split(',')
                if (c.size < 3 || c[2] == "RUMBLE_STRIP") return@forEachIndexed
                val lat = c[0].toDoubleOrNull() ?: return@forEachIndexed
                val lon = c[1].toDoubleOrNull() ?: return@forEachIndexed
                points.add(Hazard("pardal:lombada:$i", lat, lon, "lombada", ""))
            }
        }
        bundled = points
        return points
    }

    private fun load(loc: Location) {
        fetching = true
        lastFetch = System.currentTimeMillis()
        attemptedCenter = Location(loc)
        val lat = loc.latitude
        val lon = loc.longitude
        Thread {
            try {
                val source = readBundled()
                // Apenas os pontos na região exibida entram nas camadas; o banco
                // completo do estado permanece guardado e disponível sem internet.
                val near = source.filter { h ->
                    val latMeters = (h.lat - lat) * 111320.0
                    val lonMeters = (h.lon - lon) * 111320.0 * cos(Math.toRadians(lat))
                    latMeters * latMeters + lonMeters * lonMeters <= 5500.0 * 5500.0
                }
                handler.post {
                    if (!active) return@post
                    hazards = near + signalHazards(lat, lon)
                    center = Location(loc)
                    lastFailure = false
                    warned.clear()
                    classifyRouteInBackground()
                    redraw()
                    if (Prefs.getInt(context, "alert_semaforo") == 0) trafficSignals.fetchAt(lat, lon)
                    if (near.isEmpty()) {
                        onStatus("Sem pontos cadastrados nesta região da base offline de SP")
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    if (!active) return@post
                    lastFailure = true
                    if (System.currentTimeMillis() - lastErrorMessage > 120000L) {
                        lastErrorMessage = System.currentTimeMillis()
                        onStatus("Erro ao ler banco local de radares: ${(e.message ?: "desconhecido").take(60)}")
                    }
                }
            } finally {
                handler.post {
                    fetching = false
                    if (databaseReloadPending && active) {
                        databaseReloadPending = false
                        (center ?: attemptedCenter)?.let { load(Location(it)) }
                    }
                }
            }
        }.start()
    }
}
