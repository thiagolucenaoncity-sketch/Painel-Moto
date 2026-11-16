package com.moto.painel

import android.app.Activity
import android.content.Intent
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

// Tela de abertura: mostra o logo do Ravo ou a foto escolhida e depois abre o app
class SplashActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = Prefs.getInt(this, "splash_mode") // 0 = logo, 1 = minha foto, 2 = desligada
        if (mode == 2) {
            goNext()
            return
        }

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val photo = if (mode == 1) loadPhoto() else null

        if (photo != null) {
            val img = ImageView(this).apply {
                setImageBitmap(photo)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            frame.addView(
                img,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        } else {
            val size = (220 * resources.displayMetrics.density).toInt()
            val logo = ImageView(this).apply {
                setImageResource(R.drawable.ravo_icon)
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

    private fun loadPhoto() = try {
        val f = File(filesDir, "splash.jpg")
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
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
