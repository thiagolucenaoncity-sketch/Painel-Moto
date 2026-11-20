package com.moto.painel

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Atualizador somente do APK distribuído pelo GitHub Releases.
 * A instalação é SEMPRE confirmada pelo usuário no instalador do Android.
 * Para distribuição pela Google Play, substituir por Play In-App Updates;
 * a política da Play proíbe REQUEST_INSTALL_PACKAGES para autoatualização.
 */
internal class RavoUpdater(
    private val activity: Activity,
    private val canShowPrompt: () -> Boolean = { true }
) {
    private data class Release(
        val tag: String,
        val code: Int,
        val assetName: String,
        val url: String,
        val bytes: Long,
        val sha256: String?
    )

    private val prefs = activity.getSharedPreferences("ravo_updates", Context.MODE_PRIVATE)
    private val downloads = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val ui = Handler(Looper.getMainLooper())
    private var foreground = false
    private var closed = false
    private var fetching = false
    private var checkingDownload = false
    private var showingDialog = false
    private var latest: Release? = null

    private val finished = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id > 0 && id == prefs.getLong("download_id", -1L)) {
                checkFinishedDownload()
            }
        }
    }

    init {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(finished, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(finished, filter)
        }
    }

    fun onResume(checkAutomatically: Boolean = true) {
        foreground = true
        if (prefs.getBoolean("await_permission", false)) {
            val allowed = activity.packageManager.canRequestPackageInstalls()
            prefs.edit().putBoolean("await_permission", false)
                .putLong("install_snooze_until",
                    if (allowed) 0L else System.currentTimeMillis() + 24L * 60 * 60 * 1000)
                .apply()
            if (allowed) installVerified()
        } else {
            checkFinishedDownload()
        }
        if (checkAutomatically) checkNow()
    }

    fun onPause() {
        foreground = false
    }

    fun close() {
        closed = true
        foreground = false
        try {
            activity.unregisterReceiver(finished)
        } catch (_: IllegalArgumentException) {
            // Receiver já desregistrado.
        }
    }

    private fun installedCode(): Long = try {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (_: Exception) { 0L }

    fun checkNow(force: Boolean = false) {
        if (closed || fetching) return
        val now = System.currentTimeMillis()
        val pendingId = prefs.getLong("download_id", -1L)
        val pendingCode = prefs.getInt("download_code", 0)
        if (pendingId > 0 && pendingCode > installedCode()) {
            if (force && foreground && prefs.getLong("verified_id", -1L) == pendingId) {
                // Repetir instalação caso o usuário tenha cancelado no Android.
                prefs.edit().putLong("install_attempt_id", -1L)
                    .putLong("install_snooze_until", 0L).apply()
                installVerified()
            } else {
                checkFinishedDownload()
                if (force && foreground) {
                    toast("Uma atualização do RAVO já está sendo baixada ou instalada.")
                }
            }
            return
        }
        if (!force && now - prefs.getLong("checked_at", 0L) < 6L * 60 * 60 * 1000) return

        fetching = true
        Thread {
            val result = try {
                val conn = URL(UpdateReleasePolicy.API).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 6500
                    conn.readTimeout = 6500
                    conn.setRequestProperty("User-Agent", "RAVO-Android-Updater")
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                    if (conn.responseCode != 200) null
                    else {
                        val json = conn.inputStream.bufferedReader(Charsets.UTF_8).use {
                            // Metadados, não o APK. Evita resposta excessiva na memória.
                            it.readText().take(600_000)
                        }
                        parseRelease(JSONObject(json))
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) { null }
            ui.post {
                if (closed) return@post
                fetching = false
                if (result == null) {
                    if (force && foreground) toast("Não foi possível consultar as atualizações.")
                    return@post
                }
                prefs.edit().putLong("checked_at", System.currentTimeMillis()).apply()
                if (result.code.toLong() <= installedCode()) {
                    if (force && foreground) {
                        toast("Você já está usando a versão mais recente do RAVO.")
                    }
                    return@post
                }
                latest = result
                prompt(result, force)
            }
        }.start()
    }

    private fun parseRelease(json: JSONObject): Release? {
        val tag = json.optString("tag_name")
        val code = UpdateReleasePolicy.versionCode(tag) ?: return null
        val expectedName = UpdateReleasePolicy.expectedAssetName(tag) ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (name != expectedName) continue
            val url = asset.optString("browser_download_url")
            val bytes = asset.optLong("size", 0L)
            if (!UpdateReleasePolicy.isValidAsset(tag, name, url, bytes)) continue
            val digest = if (asset.isNull("digest")) "" else asset.optString("digest", "")
            // Havendo digest publicado, não aceitar hash malformado.
            if (digest.isNotBlank() && !Regex("^sha256:[a-fA-F0-9]{64}$").matches(digest)) {
                return null
            }
            return Release(tag, code, name, url, bytes,
                digest.takeIf { it.isNotBlank() }?.substringAfter(':')?.lowercase())
        }
        return null
    }

    private fun prompt(release: Release, force: Boolean = false) {
        if (closed || !foreground || !canShowPrompt() || showingDialog) return
        val declinedCode = prefs.getInt("declined_code", 0)
        val declinedAt = prefs.getLong("declined_at", 0L)
        if (!force && declinedCode == release.code &&
            System.currentTimeMillis() - declinedAt < 24L * 60 * 60 * 1000L
        ) return

        showingDialog = true
        AlertDialog.Builder(activity)
            .setTitle("Atualização disponível")
            .setMessage(
                "RAVO ${release.tag.removePrefix("v")} disponível.\\n\\n" +
                    "Deseja atualizar agora? O download tem aproximadamente " +
                    "${(release.bytes / 1_000_000.0).toInt()} MB. " +
                    "O Android pedirá sua confirmação para instalar."
            )
            .setNegativeButton("Não, depois") { _, _ ->
                prefs.edit().putInt("declined_code", release.code)
                    .putLong("declined_at", System.currentTimeMillis()).apply()
            }
            .setPositiveButton("Sim, atualizar") { _, _ -> startDownload(release) }
            .setOnDismissListener { showingDialog = false }
            .show()
    }

    private fun startDownload(release: Release) {
        if (closed) return
        try {
            // Cada versão tem nome exclusivo. Sem permissão de armazenamento.
            val folder = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IllegalStateException("Armazenamento indisponível")
            File(folder, release.assetName).let { if (it.exists()) it.delete() }
            val request = DownloadManager.Request(Uri.parse(release.url))
                .setTitle("Atualização RAVO ${release.tag}")
                .setDescription("Baixando versão assinada pelo projeto RAVO")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalFilesDir(
                    activity, Environment.DIRECTORY_DOWNLOADS, release.assetName
                )
            val id = downloads.enqueue(request)
            prefs.edit()
                .putLong("download_id", id)
                .putInt("download_code", release.code)
                .putString("download_file", release.assetName)
                .putString("download_sha256", release.sha256 ?: "")
                .putLong("verified_id", -1L)
                .putLong("install_attempt_id", -1L)
                .putBoolean("await_permission", false)
                .putLong("install_snooze_until", 0L)
                .apply()
            toast("Atualização sendo baixada. O Android avisará quando concluir.")
        } catch (_: Exception) {
            toast("Falha ao iniciar download. Verifique sua conexão e o armazenamento.")
        }
    }

    private fun checkFinishedDownload() {
        if (closed || checkingDownload) return
        if (System.currentTimeMillis() < prefs.getLong("install_snooze_until", 0L)) return
        val id = prefs.getLong("download_id", -1L)
        if (id <= 0 || prefs.getInt("download_code", 0) <= installedCode()) return
        if (prefs.getLong("install_attempt_id", -1L) == id) return

        val status = try {
            downloads.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
        } catch (_: Exception) { null }
        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> verifyDownloaded(id)
            DownloadManager.STATUS_FAILED -> {
                prefs.edit().remove("download_id").apply()
                if (foreground) toast("Falha no download da atualização. Tente novamente.")
            }
        }
    }

    private fun verifyDownloaded(id: Long) {
        if (checkingDownload || closed) return
        if (prefs.getLong("verified_id", -1L) == id) {
            if (foreground) installVerified()
            return
        }
        checkingDownload = true
        val name = prefs.getString("download_file", "") ?: ""
        val expectedCode = prefs.getInt("download_code", -1)
        val sha = prefs.getString("download_sha256", "") ?: ""
        Thread {
            val verified = try {
                if (!Regex("^RAVO_atualizavel_v1\\.1\\.[0-9]+\\.apk$").matches(name)) {
                    false
                } else {
                    val file = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)
                    val packageInfo = activity.packageManager.getPackageArchiveInfo(
                        file.absolutePath, 0
                    )
                    val code = if (Build.VERSION.SDK_INT >= 28) {
                        packageInfo?.longVersionCode ?: -1L
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo?.versionCode?.toLong() ?: -1L
                    }
                    if (!file.isFile || packageInfo?.packageName != activity.packageName ||
                        code != expectedCode.toLong()
                    ) false
                    else if (sha.isBlank()) true
                    else {
                        val md = MessageDigest.getInstance("SHA-256")
                        file.inputStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer)
                                if (n == -1) break
                                md.update(buffer, 0, n)
                            }
                        }
                        md.digest().joinToString("") { "%02x".format(it) } == sha
                    }
                }
            } catch (_: Exception) { false }
            ui.post {
                checkingDownload = false
                if (closed || prefs.getLong("download_id", -1L) != id) return@post
                if (verified) {
                    prefs.edit().putLong("verified_id", id).apply()
                    if (foreground) installVerified()
                } else {
                    prefs.edit().remove("download_id").apply()
                    if (foreground) toast("O APK baixado não passou na verificação. Tente novamente.")
                }
            }
        }.start()
    }

    private fun installVerified() {
        if (closed || !foreground) return
        val id = prefs.getLong("download_id", -1L)
        if (id <= 0 || prefs.getLong("verified_id", -1L) != id ||
            prefs.getLong("install_attempt_id", -1L) == id) return

        // Somente versões APK fora da Play; nenhuma instalação silenciosa.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            if (showingDialog) return
            showingDialog = true
            AlertDialog.Builder(activity)
                .setTitle("Permitir atualização do RAVO")
                .setMessage(
                    "Para instalar o APK assinado, o Android precisa que você autorize " +
                    "o RAVO como fonte de instalação. Essa permissão não instala nada " +
                    "sem sua confirmação."
                )
                .setNegativeButton("Depois") { _, _ ->
                    prefs.edit().putLong("install_snooze_until",
                        System.currentTimeMillis() + 24L * 60 * 60 * 1000).apply()
                }
                .setPositiveButton("Abrir configurações") { _, _ ->
                    prefs.edit().putBoolean("await_permission", true).apply()
                    try {
                        activity.startActivity(Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        ))
                    } catch (_: Exception) {
                        toast("Não foi possível abrir as permissões de instalação.")
                    }
                }
                .setOnDismissListener { showingDialog = false }
                .show()
            return
        }

        val uri = downloads.getUriForDownloadedFile(id)
        if (uri == null) {
            toast("Arquivo de atualização indisponível.")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(activity.contentResolver, "APK RAVO", uri)
            }
            // Evita reabrir o instalador se o usuário o cancelar e voltar.
            prefs.edit().putLong("install_attempt_id", id).apply()
            activity.startActivity(intent)
        } catch (_: Exception) {
            prefs.edit().putLong("install_attempt_id", -1L).apply()
            toast("Não foi possível abrir o instalador do Android.")
        }
    }

    private fun toast(message: String) =
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
