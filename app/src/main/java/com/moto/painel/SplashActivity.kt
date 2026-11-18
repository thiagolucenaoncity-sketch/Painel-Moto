package com.moto.painel

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File

// Abertura oficial animada -> foto opcional da moto -> painel.
class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var done = false
    private var spinner: ObjectAnimator? = null
    private var dots: ValueAnimator? = null
    private var root: FrameLayout? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = Prefs.getInt(this, "splash_mode")
        if (mode == 2) {
            goNext()
            return
        }
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(2, 12, 30)) }
        root = frame
        setContentView(frame)
        showOfficial(frame)
        frame.setOnClickListener { goNext() }
        handler.postDelayed({
            if (mode == 1 && showPhoto(frame)) {
                handler.postDelayed({ goNext() }, 2000L)
            } else {
                goNext()
            }
        }, 1500L)
    }

    private fun showOfficial(frame: FrameLayout) {
        val wallpaper = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Arquivo opcional enviado ao projeto: app/src/main/assets/ravo_splash.png
            val bitmap = try {
                assets.open("ravo_splash.png").use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null }
            if (bitmap != null) setImageBitmap(bitmap) else setImageResource(R.mipmap.ravo_icon)
        }
        frame.addView(wallpaper, FrameLayout.LayoutParams(-1, -1))

        val loading = FrameLayout(this)
        val lp = FrameLayout.LayoutParams(dp(160), dp(112), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(46)
        }
        frame.addView(loading, lp)

        val ring = TextView(this).apply {
            text = "◌"
            textSize = 70f
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
        }
        loading.addView(ring, FrameLayout.LayoutParams(dp(90), dp(90), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val bike = TextView(this).apply {
            text = "🏍"
            textSize = 31f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        loading.addView(bike, FrameLayout.LayoutParams(dp(90), dp(90), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val label = TextView(this).apply {
            text = "Carregando"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        loading.addView(label, FrameLayout.LayoutParams(-1, dp(32), Gravity.BOTTOM))

        spinner = ObjectAnimator.ofFloat(ring, "rotation", 0f, 360f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        dots = ValueAnimator.ofInt(0, 3).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { label.text = "Carregando" + ".".repeat(it.animatedValue as Int) }
            start()
        }
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(350L).start()
    }

    private fun showPhoto(frame: FrameLayout): Boolean {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val photo = loadPhoto(landscape) ?: return false
        spinner?.cancel()
        dots?.cancel()
        frame.setOnClickListener { goNext() }
        val photoView = ImageView(this).apply {
            setImageBitmap(photo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }
        frame.addView(photoView, FrameLayout.LayoutParams(-1, -1))
        photoView.animate().alpha(1f).setDuration(350L).start()
        return true
    }

    private fun loadPhoto(landscape: Boolean) = try {
        val first = if (landscape) "splash_l.jpg" else "splash_p.jpg"
        val second = if (landscape) "splash_p.jpg" else "splash_l.jpg"
        val file = listOf(first, second).map { File(filesDir, it) }.firstOrNull { it.exists() }
        if (file != null) BitmapFactory.decodeFile(file.absolutePath) else null
    } catch (_: Exception) { null }

    private fun goNext() {
        if (done) return
        done = true
        spinner?.cancel()
        dots?.cancel()
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, CrashActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        spinner?.cancel()
        dots?.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
