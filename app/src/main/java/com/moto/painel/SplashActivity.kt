package com.moto.painel

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File

// Tela de abertura: mostra o logo do Ravo ou a foto certa para a orientação e depois abre o app
class SplashActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = Prefs.getInt(this, "splash_mode") // 0 = logo, 1 = minhas fotos, 2 = desligada
        if (mode == 2) {
            goNext()
            return
        }

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val photo = if (mode == 1) loadPhoto(landscape) else null

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        if (photo != null) {
            // Mostra a foto inteira, sem cortar; sobra faixa preta se a proporção não bater
            val img = ImageView(this).apply {
                setImageBitmap(photo)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            frame.addView(
                img,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        } else {
            val size = (220 * resources.displayMetrics.density).toInt()
            val logo = ImageView(this).apply {
                setImageResource(R.mipmap.ravo_icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            frame.addView(logo, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        }

        frame.setOnClickListener { goNext() }
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(600).start()
        setContentView(frame)

        handler.postDelayed({ goNext() }, 2500)
    }

    // Carrega a foto da orientação atual; se não existir, usa a outra
    private fun loadPhoto(landscape: Boolean) = try {
        val first = if (landscape) "splash_l.jpg" else "splash_p.jpg"
        val second = if (landscape) "splash_p.jpg" else "splash_l.jpg"
        val f = listOf(first, second)
            .map { File(filesDir, it) }
            .firstOrNull { it.exists() }
        if (f != null) BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (e: Exception) {
        null
    }

    private fun goNext() {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, CrashActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
