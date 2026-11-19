package com.moto.painel

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Road speed limits are NOT radar speed limits. Queries OSM ways tagged with
 * an explicit numeric maxspeed, keeps the last area offline and only reports a
 * speed when the nearest road is unambiguous. No default from road category.
 */
internal class RoadSpeedLimitSource(
    context: Context,
    private val onChanged: (Int?) -> Unit
) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val file = File(app.filesDir, "ravo_road_maxspeed_osm.json")
    private var segments: List<RoadSpeedLimits.Segment> = emptyList()
    private var cachedCenter: Location? = null
    private var latestPosition: Location? = null
    private var lastShown: Int? = null
    private var lastComputedAt = 0L
    private var lastComputedPosition: Location? = null
    private var lastSuccessAt = 0L
    private var lastAttemptAt = 0L
    private var fetching = false
    private var active = true

    init {
        Thread {
            try {
                if (!file.isFile) return@Thread
                val raw = JSONObject(file.readText(Charsets.UTF_8))
                val decoded = decode(raw) ?: return@Thread
                handler.post {
                    if (!active || cachedCenter != null) return@post
                    cachedCenter = decoded.first
                    segments = decoded.second
                    lastSuccessAt = raw.optLong("ravoUpdated", 0L)
                    latestPosition?.let { onLocation(it) }
                }
            } catch (_: Exception) {
                // No cache yet: absence of data is not a reason to invent a limit.
            }
        }.start()
    }

    fun close() { active = false }

    private fun connected(): Boolean = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        network != null && cm.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { false }

    fun onLocation(loc: Location) {
        if (!active) return
        latestPosition = Location(loc)
        val now = System.currentTimeMillis()
        val moved = lastComputedPosition?.distanceTo(loc) ?: Float.MAX_VALUE
        if (moved > 10f || now - lastComputedAt > 4000L) {
            lastComputedAt = now
            lastComputedPosition = Location(loc)
            val closeToCache = (cachedCenter?.distanceTo(loc) ?: Float.MAX_VALUE) < 850f
            val bearing = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 2.5f)
                loc.bearing else null
            val speed = if (closeToCache && (!loc.hasAccuracy() || loc.accuracy <= 45f))
                RoadSpeedLimits.nearest(loc.latitude, loc.longitude, bearing, segments)
            else null
            if (speed != lastShown) {
                lastShown = speed
                onChanged(speed)
            }
        }
        if (fetching || now - lastAttemptAt < 70000L || !connected()) return
        val displacement = cachedCenter?.distanceTo(loc) ?: Float.MAX_VALUE
        if (displacement > 370f || now - lastSuccessAt > 15L * 60L * 1000L) {
            fetch(loc)
        }
    }

    private fun decode(json: JSONObject): Pair<Location, List<RoadSpeedLimits.Segment>>? {
        val lat = json.optDouble("ravoLat", Double.NaN)
        val lon = json.optDouble("ravoLon", Double.NaN)
        if (!lat.isFinite() || !lon.isFinite()) return null
        val center = Location("osm-speed-cache").apply {
            latitude = lat
            longitude = lon
        }
        val roads = ArrayList<RoadSpeedLimits.Segment>()
        val elements = json.optJSONArray("elements") ?: return null
        val allowed = setOf(
            "motorway", "motorway_link", "trunk", "trunk_link",
            "primary", "primary_link", "secondary", "secondary_link",
            "tertiary", "tertiary_link", "residential", "unclassified",
            "living_street", "service"
        )
        for (i in 0 until minOf(elements.length(), 2000)) {
            val road = elements.optJSONObject(i) ?: continue
            if (road.optString("type") != "way") continue
            val tags = road.optJSONObject("tags") ?: continue
            if (tags.optString("highway") !in allowed) continue
            val kmh = RoadSpeedLimits.parseKmh(tags.optString("maxspeed")) ?: continue
            val geometry = road.optJSONArray("geometry") ?: continue
            for (j in 1 until geometry.length()) {
                if (roads.size >= 14000) break
                val a = geometry.optJSONObject(j - 1) ?: continue
                val b = geometry.optJSONObject(j) ?: continue
                val aLat = a.optDouble("lat", Double.NaN)
                val aLon = a.optDouble("lon", Double.NaN)
                val bLat = b.optDouble("lat", Double.NaN)
                val bLon = b.optDouble("lon", Double.NaN)
                if (!aLat.isFinite() || !aLon.isFinite() ||
                    !bLat.isFinite() || !bLon.isFinite()) continue
                roads.add(RoadSpeedLimits.Segment(aLat, aLon, bLat, bLon, kmh))
            }
        }
        return center to roads
    }

    private fun fetch(pos: Location) {
        fetching = true
        lastAttemptAt = System.currentTimeMillis()
        val lat = pos.latitude
        val lon = pos.longitude
        Thread {
            try {
                val query = "[out:json][timeout:18];way(around:700," +
                    lat + "," + lon + ")[\"highway\"][\"maxspeed\"];out geom;"
                val endpoints = listOf(
                    "https://overpass.private.coffee/api/interpreter",
                    "https://overpass-api.de/api/interpreter"
                )
                var decoded: Pair<Location, List<RoadSpeedLimits.Segment>>? = null
                var payload: ByteArray? = null
                for (endpoint in endpoints) {
                    try {
                        val conn = URL(endpoint).openConnection() as HttpURLConnection
                        try {
                            conn.requestMethod = "POST"
                            conn.doOutput = true
                            conn.connectTimeout = 7000
                            conn.readTimeout = 18000
                            conn.setRequestProperty(
                                "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"
                            )
                            conn.setRequestProperty("User-Agent", "RAVO-Android/1.1 (OSM maxspeed lookup)")
                            val post = "data=" + URLEncoder.encode(query, "UTF-8")
                            conn.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }
                            if (conn.responseCode !in 200..299) continue
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            conn.inputStream.use { input ->
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    require(out.size() + n <= 2_000_000) { "Overpass maxspeed response too big" }
                                    out.write(buffer, 0, n)
                                }
                            }
                            val json = JSONObject(out.toString("UTF-8"))
                            json.put("ravoLat", lat)
                            json.put("ravoLon", lon)
                            json.put("ravoUpdated", System.currentTimeMillis())
                            val value = decode(json) ?: continue
                            decoded = value
                            payload = json.toString().toByteArray(Charsets.UTF_8)
                            break
                        } finally { conn.disconnect() }
                    } catch (_: Exception) { /* Try another OSM server. */ }
                }
                val updated = decoded
                if (updated != null) {
                    // Write valid data atomically, retaining the last offline area on error.
                    try {
                        val atomic = AtomicFile(file)
                        val out = atomic.startWrite()
                        try {
                            out.write(payload!!)
                            atomic.finishWrite(out)
                        } catch (e: Exception) {
                            atomic.failWrite(out)
                        }
                    } catch (_: Exception) { /* Memory cache still works. */ }
                    handler.post {
                        if (!active) return@post
                        cachedCenter = updated.first
                        segments = updated.second
                        lastSuccessAt = System.currentTimeMillis()
                        lastComputedAt = 0L
                        latestPosition?.let { onLocation(it) }
                    }
                }
            } catch (_: Exception) { /* Optional source: never affects radar alerts. */ }
            finally { handler.post { fetching = false } }
        }.start()
    }
}
