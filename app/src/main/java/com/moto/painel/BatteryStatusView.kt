package com.moto.painel

import android.os.SystemClock
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

/**
 * Indicador do RAVO baseado em PNGs de verdade, sem emojis.
 *
 * O sprite PNG tem 21 quadros (0% a 100%, de 5 em 5) dispostos
 * em 3 colunas; a porcentagem ao lado sempre mostra o valor real.
 * Carregando: preenchimento progressivo e raio piscando.
 * Descarregando: brilho suave.
 * Ate 15%: fica vermelho e pisca, mesmo se estiver conectado.
 */
internal class BatteryStatusView(context: Context, labelColor: Int) : LinearLayout(context) {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val frameImage = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Bateria"
    }
    private val boltImage = ImageView(context).apply {
        setImageResource(R.drawable.ravo_battery_bolt)
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }
    private val percentageView = TextView(context).apply {
        setTextColor(labelColor)
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        text = "--%"
    }
    private val sheet: Bitmap by lazy {
        BitmapFactory.decodeResource(
            resources, R.drawable.ravo_battery_sprite,
            BitmapFactory.Options().apply { inScaled = false }
        ) ?: error("PNG dos indicadores da bateria indisponivel")
    }
    private val frames = HashMap<Int, Bitmap>(21)
    private var percentage = -1
    private var charging = false
    private val regularColor = labelColor

    // Independente da escala de animacoes do Android (que pode estar zerada).
    // Manter o raio animado mesmo com o celular conectado e carga estavel.
    private var animationStartedAt = 0L
    private val frameTick = object : Runnable {
        override fun run() {
            if (isAttachedToWindow) {
                if (charging || BatteryUiPolicy.isCritical(percentage)) {
                    val elapsed = SystemClock.uptimeMillis() - animationStartedAt
                    redraw((elapsed % 1400L) / 1400f)
                }
                postDelayed(this, if (charging || BatteryUiPolicy.isCritical(percentage)) 80L else 450L)
            }
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END

        val graphic = FrameLayout(context)
        graphic.addView(
            frameImage, FrameLayout.LayoutParams(dp(44), dp(26), Gravity.CENTER)
        )
        graphic.addView(
            boltImage, FrameLayout.LayoutParams(dp(12), dp(18), Gravity.CENTER)
        )
        addView(graphic, LayoutParams(dp(44), dp(27)))
        addView(
            percentageView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(4)
            }
        )
    }

    fun update(pct: Int, isCharging: Boolean) {
        val newValue = pct.coerceIn(0, 100)
        if (percentage == newValue && charging == isCharging) return
        percentage = newValue
        charging = isCharging

        percentageView.text = "$newValue%"
        val critical = BatteryUiPolicy.isCritical(newValue)
        percentageView.setTextColor(
            if (critical) 0xFFFF4D4D.toInt() else regularColor
        )
        boltImage.visibility = if (charging) View.VISIBLE else View.GONE
        contentDescription = "Bateria $newValue por cento" +
            (if (critical) ", bateria fraca" else "") +
            (if (charging) ", carregando" else ", nao carregando")
        if (isAttachedToWindow) {
            // Toda conexão/desconexão reinicia o ciclo de preenchimento.
            animationStartedAt = SystemClock.uptimeMillis()
        }
        redraw(0f)
    }

    private fun bitmapFor(index: Int): Bitmap =
        frames.getOrPut(index) {
            // 3 colunas x 7 linhas; cada quadro PNG mede 108 x 56 px.
            Bitmap.createBitmap(sheet, (index % 3) * 108, (index / 3) * 56, 108, 56)
        }

    private fun redraw(phase: Float) {
        if (percentage < 0) return

        val critical = BatteryUiPolicy.isCritical(percentage)
        frameImage.setImageBitmap(
            bitmapFor(BatteryUiPolicy.frameFor(percentage, charging, phase))
        )

        when {
            critical -> {
                // Abaixo ou igual a 15% pisca vermelho; o raio permanece animado.
                val visible = if (phase < 0.5f) 1f else 0.23f
                frameImage.alpha = visible
                percentageView.alpha = visible
                boltImage.alpha = if (charging) 0.55f + 0.45f * visible else 1f
            }
            charging -> {
                // Progresso animado na imagem PNG, sem alterar o percentual real.
                frameImage.alpha = 1f
                percentageView.alpha = 1f
                boltImage.alpha = 0.2f + 0.8f * sin(PI * phase).toFloat()
                val pulse = 1.0f + 0.18f * sin(PI * phase).toFloat()
                boltImage.scaleX = pulse
                boltImage.scaleY = pulse
            }
            else -> {
                // Sem carregador, a bateria fica fixa e nao pisca.
                frameImage.alpha = 1f
                percentageView.alpha = 1f
                boltImage.alpha = 1f
                boltImage.scaleX = 1f
                boltImage.scaleY = 1f
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animationStartedAt = SystemClock.uptimeMillis()
        removeCallbacks(frameTick)
        post(frameTick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameTick)
        super.onDetachedFromWindow()
    }
}
