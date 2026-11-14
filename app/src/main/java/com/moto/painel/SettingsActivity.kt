package com.moto.painel

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val accent = Prefs.accentColor(this)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF06142B.toInt())
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        col.addView(TextView(this).apply {
            text = "Configurações"
            textSize = 26f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })

        col.addView(section("APARÊNCIA", accent))
        col.addView(cycler("Plano de fundo", Prefs.BG_NAMES, "bg"))
        col.addView(cycler("Cor de destaque", Prefs.ACCENT_NAMES, "accent"))
        col.addView(cycler("Velocímetro", Prefs.GAUGE_NAMES, "gauge"))
        col.addView(cycler("Ícone no mapa", Prefs.MARKER_NAMES, "marker"))

        col.addView(section("CORES DO VELOCÍMETRO", accent))
        col.addView(cycler("Cor do velocímetro", Prefs.COLOR_NAMES, "arc_color"))
        col.addView(cycler("Cor dos números", Prefs.COLOR_NAMES, "num_color"))
        col.addView(cycler("Cor do texto km/h", Prefs.COLOR_NAMES, "unit_color"))

        col.addView(section("UNIDADES", accent))
        col.addView(cycler("Medidas", Prefs.UNIT_NAMES, "units"))

        col.addView(section("VEÍCULO", accent))
        val brandInput = field("Marca (ex: Honda)", Prefs.getStr(this, "brand"))
        val modelInput = field("Modelo (ex: CG 160 Titan)", Prefs.getStr(this, "model"))
        col.addView(brandInput, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
        col.addView(modelInput, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })

        val saveBtn = Button(this).apply {
            text = "Salvar veículo"
            isAllCaps = false
            setTextColor(Color.BLACK)
            setBackgroundColor(accent)
            setOnClickListener {
                Prefs.save(this@SettingsActivity, "brand", brandInput.text.toString().trim())
                Prefs.save(this@SettingsActivity, "model", modelInput.text.toString().trim())
                Toast.makeText(this@SettingsActivity, "Veículo salvo", Toast.LENGTH_SHORT).show()
            }
        }
        col.addView(saveBtn, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })

        val backBtn = Button(this).apply {
            text = "Voltar ao painel"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF14284A.toInt())
            setOnClickListener { finish() }
        }
        col.addView(backBtn, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(24) })

        return ScrollView(this).apply {
            addView(col, ViewGroup.LayoutParams(match, wrap))
        }
    }

    private fun section(title: String, accent: Int) = TextView(this).apply {
        text = title
        textSize = 13f
        setTextColor(accent)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(24), 0, 0)
    }

    // Botão que vai trocando de opção a cada toque
    private fun cycler(label: String, names: Array<String>, key: String): Button {
        val b = Button(this)
        fun show(i: Int) {
            b.text = "$label: ${names[i]}"
        }
        show(Prefs.getInt(this, key).coerceIn(0, names.size - 1))
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setBackgroundColor(0xFF14284A.toInt())
        b.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        b.setOnClickListener {
            val next = (Prefs.getInt(this, key) + 1) % names.size
            Prefs.save(this, key, next)
            show(next)
        }
        return b
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        setHint(hint)
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF7F93B5.toInt())
        setBackgroundColor(0xFF14284A.toInt())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setSingleLine(true)
    }
}
