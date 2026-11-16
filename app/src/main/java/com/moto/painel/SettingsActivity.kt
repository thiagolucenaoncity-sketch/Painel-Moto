package com.moto.painel

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class SettingsActivity : Activity() {

    private val reqPortrait = 42
    private val reqLandscape = 43

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
        col.addView(cycler("Cor de destaque", Prefs.COLOR_NAMES, "accent_c"))
        col.addView(cycler("Cor do fundo", Prefs.COLOR_NAMES, "bg_c"))
        col.addView(cycler("Cor da grade", Prefs.COLOR_NAMES, "grid_c"))
        col.addView(cycler("Cor dos cartões", Prefs.COLOR_NAMES, "card_c"))
        col.addView(cycler("Cor do relógio", Prefs.COLOR_NAMES, "clock_c"))
        col.addView(cycler("Cor dos textos", Prefs.COLOR_NAMES, "label_c"))
        col.addView(cycler("Ícone no mapa", Prefs.MARKER_NAMES, "marker"))

        col.addView(section("VELOCÍMETRO", accent))
        col.addView(cycler("Estilo", Prefs.GAUGE_NAMES, "gauge"))
        col.addView(cycler("Cor do velocímetro", Prefs.COLOR_NAMES, "arc_color"))
        col.addView(cycler("Cor dos números", Prefs.COLOR_NAMES, "num_color"))
        col.addView(cycler("Cor do km/h", Prefs.COLOR_NAMES, "unit_color"))
        col.addView(cycler("Cor das estatísticas", Prefs.COLOR_NAMES, "stat_c"))

        val resetColors = Button(this).apply {
            text = "Restaurar cores padrão"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF14284A.toInt())
            setOnClickListener {
                Prefs.resetColors(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, "Cores restauradas", Toast.LENGTH_SHORT).show()
                setContentView(buildUi())
            }
        }
        col.addView(resetColors, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })

        col.addView(section("MAPA", accent))
        col.addView(cycler("Prédios 3D", arrayOf("Desligados", "Ligados"), "buildings"))

        col.addView(section("ABERTURA DO APP", accent))
        col.addView(cycler("Tela de abertura", arrayOf("Logo Ravo", "Minhas fotos", "Desligada"), "splash_mode"))
        col.addView(pickButton("Foto para celular em pé", reqPortrait), LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })
        col.addView(pickButton("Foto para celular deitado", reqLandscape), LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })

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

    // Botão que abre a galeria para escolher a foto (retrato ou paisagem)
    private fun pickButton(label: String, requestCode: Int): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF14284A.toInt())
        setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            this@SettingsActivity.startActivityForResult(intent, requestCode)
        }
    }

    // Recebe a foto escolhida e salva no arquivo da orientação correspondente
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == reqPortrait || requestCode == reqLandscape) && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val target = if (requestCode == reqPortrait) "splash_p.jpg" else "splash_l.jpg"
            if (saveSplashPhoto(uri, target)) {
                Prefs.save(this, "splash_mode", 1)
                Toast.makeText(this, "Foto salva", Toast.LENGTH_SHORT).show()
                setContentView(buildUi())
            } else {
                Toast.makeText(this, "Não consegui abrir essa foto", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Copia a foto para dentro do app, reduzindo o tamanho para não pesar
    private fun saveSplashPhoto(uri: Uri, fileName: String): Boolean {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false
            File(filesDir, fileName).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            return true
        } catch (e: Exception) {
            return false
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
