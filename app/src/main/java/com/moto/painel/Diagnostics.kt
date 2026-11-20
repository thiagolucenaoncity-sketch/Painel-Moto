package com.moto.painel

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics opt-in: local and export-only.
 * No coordinates, street names, paths, route, trip details or identifiers.
 */
object Diagnostics {
    private const val PREF = "ravo_diagnostics"
    private const val ENABLED = "enabled"
    private const val FILE_NAME = "ravo_diagnostics.jsonl"
    private const val MAX_BYTES = 512 * 1024L
    private val lock = Any()
    private var lastGpsLogged = 0L

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, value).apply()
        if (value) event(context, "diagnostics_enabled")
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun clear(context: Context) {
        synchronized(lock) { file(context).delete() }
    }

    fun exportFile(context: Context): File {
        val source = file(context)
        val destination = File(context.cacheDir, "ravo_diagnostics_export.txt")
        synchronized(lock) {
            if (source.exists()) source.copyTo(destination, overwrite = true)
            else destination.writeText("Nenhum diagnóstico registrado.\n")
        }
        return destination
    }

    fun event(context: Context, name: String, fields: Map<String, Any> = emptyMap()) {
        if (!enabled(context)) return
        val data = JSONObject().apply {
            put("time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                .format(Date()))
            put("event", name)
            for ((key, value) in fields) put(key, value)
        }
        synchronized(lock) {
            try {
                val f = file(context)
                if (f.exists() && f.length() > MAX_BYTES) {
                    // Keep the most recent portion; never grow without limit.
                    val lines = f.readLines()
                    f.writeText(lines.takeLast(300).joinToString("\n", postfix = "\n"))
                }
                f.appendText(data.toString() + "\n")
            } catch (_: Exception) {
                // Diagnostics must never crash the navigation app.
            }
        }
    }

    fun gpsFix(context: Context, accuracyMeters: Float) {
        if (!enabled(context)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastGpsLogged < 15000L) return
        val delta = if (lastGpsLogged == 0L) -1L else now - lastGpsLogged
        lastGpsLogged = now
        event(context, "gps_health", mapOf(
            "interval_ms" to delta,
            "accuracy_band_m" to when {
                accuracyMeters < 10f -> "<10"
                accuracyMeters < 25f -> "10-25"
                accuracyMeters < 50f -> "25-50"
                else -> "50+"
            }
        ))
    }

    fun memory(context: Context, engine: String, uiDelayMs: Long) {
        if (!enabled(context)) return
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        event(context, "performance", mapOf(
            "engine" to engine,
            "heap_used_mb" to (used / 1048576L),
            "available_ram_mb" to (info.availMem / 1048576L),
            "low_memory" to info.lowMemory,
            "ui_delay_ms" to uiDelayMs.coerceAtLeast(0L)
        ))
    }

    fun crash(context: Context, error: Throwable) {
        if (!enabled(context)) return
        event(context, "fatal_crash", mapOf(
            "exception" to (error.javaClass.simpleName.take(70)),
            "top_frame" to (error.stackTrace.firstOrNull()?.className?.take(120) ?: "unknown")
        ))
    }

    class Session(private val context: Context, private val engine: String) {
        private val handler = Handler(Looper.getMainLooper())
        private var running = false
        private var due = 0L
        private val tick = object : Runnable {
            override fun run() {
                if (!running || !enabled(context)) return
                val now = SystemClock.uptimeMillis()
                memory(context, engine, now - due)
                due = now + 15000L
                handler.postAtTime(this, due)
            }
        }
        fun start() {
            if (running || !enabled(context)) return
            running = true
            event(context, "session_start", mapOf(
                "engine" to engine,
                "android_sdk" to Build.VERSION.SDK_INT,
                "device_model" to Build.MODEL.take(80)
            ))
            due = SystemClock.uptimeMillis() + 15000L
            handler.postAtTime(tick, due)
        }
        fun stop() {
            if (!running) return
            running = false
            handler.removeCallbacks(tick)
            event(context, "session_end", mapOf("engine" to engine))
        }
    }
}
