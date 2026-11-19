package com.moto.painel

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Cartão de chegada usado nas duas telas. Não intercepta gestos do mapa,
 * não anima e não pisca quando GPS/ETA são atualizados.
 */
internal class RouteEtaCard(context: Context, accent: Int) : LinearLayout(context) {
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun field(caption: String): Pair<LinearLayout, TextView> {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        container.addView(TextView(context).apply {
            text = caption
            textSize = 9f
            letterSpacing = 0.06f
            setTextColor(0xFFBED8F3.toInt())
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val value = TextView(context).apply {
            text = "—"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = true
            maxLines = 1
        }
        container.addView(value, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return container to value
    }

    private val eta = field("CHEGADA")
    private val time = field("RESTANTE")
    private val distance = field("DISTÂNCIA")

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(7), dp(8), dp(7))
        background = GradientDrawable().apply {
            setColor(0xD90B1A33.toInt())
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), (accent and 0x00FFFFFF) or 0x77000000)
        }
        elevation = dp(3).toFloat()
        isClickable = false
        isFocusable = false
        for ((group, _) in listOf(eta, time, distance)) {
            addView(group, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        visibility = View.GONE
    }

    fun render(arrival: String, duration: String, kilometers: String) {
        if (eta.second.text.toString() != arrival) eta.second.text = arrival
        if (time.second.text.toString() != duration) time.second.text = duration
        if (distance.second.text.toString() != kilometers) distance.second.text = kilometers
        contentDescription = "Chegada prevista: $arrival. Restante: $duration. Distância: $kilometers"
    }
}
