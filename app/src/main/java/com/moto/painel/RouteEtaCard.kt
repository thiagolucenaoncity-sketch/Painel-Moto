package com.moto.painel

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Cartão de chegada usado nas duas telas. Não intercepta gestos do mapa,
 * não anima e não pisca quando GPS/ETA são atualizados.
 */
internal class RouteEtaCard(context: Context, accent: Int) : LinearLayout(context) {
    // Perfil próprio de paisagem, sem alterar a altura do cartão no retrato.
    private val labels = mutableListOf<TextView>()
    private val values = mutableListOf<TextView>()
    private val groups = mutableListOf<LinearLayout>()
    private val accentColor = accent

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun field(caption: String): Pair<LinearLayout, TextView> {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        container.addView(TextView(context).apply {
            text = caption
            textSize = 10f
            letterSpacing = 0.06f
            setTextColor(0xFFBED8F3.toInt())
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        labels.add(container.getChildAt(0) as TextView)
        groups.add(container)
        val value = TextView(context).apply {
            text = "—"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = true
            maxLines = 1
            // Mantem ETA, minutos e km legiveis no espaco entre GPS e inicio.
            setAutoSizeTextTypeUniformWithConfiguration(11, 18, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        container.addView(value, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        values.add(value)
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
            setStroke(dp(2), (accent and 0x00FFFFFF) or 0xFF000000.toInt())
        }
        elevation = dp(3).toFloat()
        isClickable = false
        isFocusable = false
        for ((group, _) in listOf(eta, time, distance)) {
            addView(group, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        visibility = View.GONE
    }

    fun setCompact(compact: Boolean) {
        if (!compact) return
        setPadding(dp(6), dp(3), dp(6), dp(3))
        labels.forEach {
            it.textSize = 8f
            it.letterSpacing = 0.03f
            it.includeFontPadding = false
        }
        values.forEach {
            it.textSize = 13f
            it.includeFontPadding = false
            it.setAutoSizeTextTypeUniformWithConfiguration(
                10, 13, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }
        groups.forEach { it.setPadding(dp(2), 0, dp(2), 0) }
        minimumHeight = 0
    }

    /**
     * Na tela inicial paisagem a previsão sai de cima do mapa e passa para
     * a parte inferior da coluna da trip, com três blocos empilhados.
     * No retrato e no mapa completo o cartão continua horizontal.
     */
    fun setLandscapeSidebar(tight: Boolean = false) {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val inset = if (tight) 3 else 6
        setPadding(dp(inset), dp(inset), dp(inset), dp(inset))
        // Guardar linhas compactas sem perder valores ou acessibilidade.
        val labelSize = if (tight) 8.5f else 10f
        val valueSize = if (tight) 16f else 19f
        val iconSize = if (tight) 18f else 24f
        val rowIconWidth = if (tight) 29 else 41
        groups.forEachIndexed { index, group ->
            val params = group.layoutParams as LayoutParams
            params.width = LayoutParams.MATCH_PARENT
            params.height = LayoutParams.WRAP_CONTENT
            params.weight = 0f
            params.topMargin = dp(if (tight) 1 else 3)
            params.bottomMargin = dp(if (tight) 1 else 3)
            group.layoutParams = params
            group.orientation = HORIZONTAL
            group.gravity = Gravity.CENTER_VERTICAL
            group.setPadding(dp(2), dp(if (tight) 1 else 3), dp(2), dp(if (tight) 1 else 3))
            val symbol = TextView(context).apply {
                text = listOf("⚑", "◷", "⌖")[index]
                textSize = iconSize
                setTextColor(accentColor)
                gravity = Gravity.CENTER
            }
            // Recuperar label/valor dos grupos antes de realocar.
            val title = labels[index]
            val value = values[index]
            group.removeView(title)
            group.removeView(value)
            val info = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(value, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            title.textSize = labelSize
            value.textSize = valueSize
            value.setAutoSizeTextTypeUniformWithConfiguration(
                if (tight) 11 else 12, valueSize.toInt(), 1, TypedValue.COMPLEX_UNIT_SP
            )
            group.addView(symbol, LayoutParams(dp(rowIconWidth), dp(if (tight) 32 else 43)))
            group.addView(info, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        // Divisórias entre linhas (no cartão, nunca inseridas fora dos limites dos grupos).
        for (index in 0 until groups.lastIndex) {
            val divider = View(context).apply { setBackgroundColor(0x665B7897) }
            addView(divider, index * 2 + 1,
                LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
        }
    }

    fun render(arrival: String, duration: String, kilometers: String) {
        if (eta.second.text.toString() != arrival) eta.second.text = arrival
        if (time.second.text.toString() != duration) time.second.text = duration
        if (distance.second.text.toString() != kilometers) distance.second.text = kilometers
        contentDescription = "Chegada prevista: $arrival. Restante: $duration. Distância: $kilometers"
    }
}
