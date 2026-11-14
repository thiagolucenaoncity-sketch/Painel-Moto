package com.moto.painel

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// Primeira tela do app: mostra o erro salvo (se houver) e depois abre o painel
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("crash", MODE_PRIVATE)
        val log = prefs.getString("log", null)

        if (log == null) {
            openMain()
            return
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "O app fechou com erro. Toque em Copiar erro e cole aqui no chat."
            setTextColor(Color.WHITE)
            textSize = 16f
        })
        val errorText = TextView(this).apply {
            text = log
            setTextColor(0xFFFF8A80.toInt())
            textSize = 11f
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(errorText) }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val copyBtn = Button(this).apply {
            text = "Copiar erro"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("erro", log))
                Toast.makeText(this@CrashActivity, "Erro copiado", Toast.LENGTH_SHORT).show()
            }
        }
        val openBtn = Button(this).apply {
            text = "Limpar e abrir o app"
            setOnClickListener {
                prefs.edit().remove("log").apply()
                openMain()
            }
        }
        col.addView(copyBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(openBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(col)
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
