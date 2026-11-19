package com.moto.painel

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Base offline-first Pardal (SP): radar/lombada são consultados no celular pelo GPS.
 * A rede só é usada para atualizar os arquivos completos, no máximo a cada 15 dias.
 * Enquanto uma atualização falha, permanecem os dados anteriores ou os assets do APK.
 */
object RadarDatabaseUpdater {
    private const val PERIOD_MS = 15L * 24L * 60L * 60L * 1000L
    private const val RETRY_MS = 6L * 60L * 60L * 1000L
    private const val MAX_CSV_BYTES = 4 * 1024 * 1024
    private const val PREF_NAME = "ravo_road_data_sync"
    private const val BASE = "https://raw.githubusercontent.com/Murkiriel/pardal/main/estados/SP/"
    private val sources = listOf("sp_radares.csv" to "radares.csv", "sp_lombadas.csv" to "lombadas.csv")
    @Volatile private var running = false

    private fun local(context: Context, file: String) = File(File(context.filesDir, "roadalerts"), file)

    fun reader(context: Context, file: String): java.io.BufferedReader {
        val downloaded = local(context, file)
        return if (downloaded.isFile) downloaded.bufferedReader()
        else context.assets.open("roadalerts/$file").bufferedReader()
    }

    fun lastUpdateMs(context: Context): Long =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getLong("success", 0L)

    fun checkWhenDue(context: Context, connected: Boolean, onUpdated: () -> Unit) {
        if (!connected || running) return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val success = prefs.getLong("success", 0L)
        val attempted = prefs.getLong("attempt", 0L)
        if (success > 0L && now - success in 0 until PERIOD_MS) return
        if (attempted > 0L && now - attempted in 0 until RETRY_MS) return
        running = true
        prefs.edit().putLong("attempt", now).apply()
        val app = context.applicationContext
        Thread {
            try {
                // Baixa e valida ambos antes de trocar qualquer base local.
                val downloads = sources.map { (file, sourceName) ->
                    val bytes = download(BASE + sourceName)
                    validate(file, bytes)
                    file to bytes
                }
                for ((file, bytes) in downloads) {
                    val target = local(app, file)
                    target.parentFile?.mkdirs()
                    val atomic = AtomicFile(target)
                    val output = atomic.startWrite()
                    try {
                        output.write(bytes)
                        atomic.finishWrite(output)
                    } catch (e: Exception) {
                        atomic.failWrite(output)
                        throw e
                    }
                }
                prefs.edit().putLong("success", System.currentTimeMillis()).apply()
                Handler(Looper.getMainLooper()).post { onUpdated() }
            } catch (_: Exception) {
                // Não descarta a versão anterior; tenta de novo depois de 6 h com internet.
            } finally {
                running = false
            }
        }.start()
    }

    private fun download(address: String): ByteArray {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 25000
            connection.setRequestProperty("Accept", "text/plain,text/csv,*/*")
            connection.setRequestProperty("User-Agent", "RAVO-Android/1.1 (Pardal road-data update)")
            if (connection.responseCode !in 200..299) {
                throw IOException("CSV HTTP ${connection.responseCode}")
            }
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > MAX_CSV_BYTES) throw IOException("CSV maior que o limite")
                    output.write(buffer, 0, n)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun validate(file: String, bytes: ByteArray) {
        val content = bytes.toString(Charsets.UTF_8)
        val expected = if (file == "sp_radares.csv") "lat,lng,kind,limit_kmh,source,active" else "lat,lng,kind"
        if (!content.startsWith(expected)) throw IOException("Cabeçalho inválido")
        val min = if (file == "sp_radares.csv") 1000 else 5000
        var rows = 0
        var validCoords = 0
        content.lineSequence().drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split(',')
            if (parts.size >= 3) {
                rows++
                val lat = parts[0].toDoubleOrNull()
                val lon = parts[1].toDoubleOrNull()
                if (lat != null && lon != null && lat in -35.0..6.0 && lon in -74.0..-32.0) {
                    validCoords++
                }
            }
        }
        if (rows < min || validCoords < rows * 0.95) {
            throw IOException("Base incompleta ou coordenadas inválidas")
        }
    }
}
