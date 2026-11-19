package com.moto.painel

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos

/**
 * Semáforos mapeados como highway=traffic_signals no OpenStreetMap.
 * Salva o resultado em disco para continuar mostrando pontos já baixados offline.
 * Não fornece informações de fase (verde/amarelo/vermelho).
 */
class TrafficSignalSource(private val context: Context, private val changed: () -> Unit) {
    data class Signal(val id: String, val lat: Double, val lon: Double)
    private val handler = Handler(Looper.getMainLooper())
    private val file = File(context.filesDir, "ravo_traffic_signals_sp.csv")
    private val points = LinkedHashMap<String, Signal>()
    private var fetching = false
    private var lastFetchMs = 0L
    private var lastFetchSucceeded = false
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var active = true

    init {
        try {
            if (file.exists()) file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val p = line.split(',')
                    if (p.size >= 3) {
                        val lat = p[1].toDoubleOrNull()
                        val lon = p[2].toDoubleOrNull()
                        if (lat != null && lon != null) points[p[0]] = Signal(p[0], lat, lon)
                    }
                }
            }
        } catch (_: Exception) { /* Cache opcional: não afeta radares. */ }
    }

    fun close() { active = false }
    fun nearby(lat: Double, lon: Double, radius: Double = 5500.0): List<Signal> {
        val cosLat = cos(Math.toRadians(lat))
        return points.values.filter { s ->
            val a = (s.lat - lat) * 111320.0
            val b = (s.lon - lon) * 111320.0 * cosLat
            a * a + b * b <= radius * radius
        }
    }

    fun fetchAt(lat: Double, lon: Double) {
        if (!active || fetching || lat !in -85.0..85.0 || lon !in -180.0..180.0) return
        val now = System.currentTimeMillis()
        val moved = kotlin.math.abs(lat - lastLat) + kotlin.math.abs(lon - lastLon) > 0.016
        if (lastFetchMs != 0L && now - lastFetchMs < 10 * 60 * 1000L && lastFetchSucceeded && !moved) return
        // Evita repetição incessante em túneis/sem sinal e tenta novamente após 2 min.
        if (lastFetchMs != 0L && now - lastFetchMs < (if (lastFetchSucceeded) 30000L else 120000L)) return
        fetching = true
        lastFetchMs = now
        lastLat = lat
        lastLon = lon
        Thread {
            try {
                val query = "[out:json][timeout:18];node(around:2300,$lat,$lon)[\"highway\"=\"traffic_signals\"];out body;"
                val endpoints = listOf(
                    "https://overpass.private.coffee/api/interpreter",
                    "https://overpass-api.de/api/interpreter"
                )
                var incoming: List<Signal>? = null
                for (endpoint in endpoints) {
                    try {
                        val conn = URL(endpoint).openConnection() as HttpURLConnection
                        try {
                            conn.requestMethod = "POST"
                            conn.doOutput = true
                            conn.connectTimeout = 8000
                            conn.readTimeout = 20000
                            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                            conn.setRequestProperty("Accept", "application/json")
                            conn.setRequestProperty("User-Agent", "RAVO-Android/1.1 (OSM traffic signals)")
                            val payload = "data=" + URLEncoder.encode(query, "UTF-8")
                            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                            if (conn.responseCode !in 200..299) continue
                            val data = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                            val arr = data.getJSONArray("elements")
                            val found = ArrayList<Signal>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val id = "osm:${obj.optLong("id")}"
                                found.add(Signal(id, obj.getDouble("lat"), obj.getDouble("lon")))
                            }
                            incoming = found
                        } finally {
                            conn.disconnect()
                        }
                        break
                    } catch (_: Exception) { /* Usa o outro servidor. */ }
                }
                if (incoming != null) {
                    lastFetchSucceeded = true
                    handler.post {
                        if (!active) return@post
                        var added = 0
                        incoming.forEach { signal ->
                            if (points.put(signal.id, signal) == null) added++
                        }
                        if (added > 0) {
                            changed()
                            Thread {
                                try {
                                    file.parentFile?.mkdirs()
                                    val snapshot = points.values.toList()
                                    file.bufferedWriter().use { out ->
                                        snapshot.forEach { out.appendLine("${it.id},${it.lat},${it.lon}") }
                                    }
                                } catch (_: Exception) { /* Sem cache o mapa online continua. */ }
                            }.start()
                        }
                    }
                } else {
                    lastFetchSucceeded = false
                }
            } catch (_: Exception) {
                lastFetchSucceeded = false
            } finally {
                handler.post { fetching = false }
            }
        }.start()
    }
}
