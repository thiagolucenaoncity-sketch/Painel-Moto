package com.moto.painel

import android.content.Context

// Guarda as escolhas do usuário no celular
object Prefs {
    private const val FILE = "painel_prefs"

    val BG_NAMES = arrayOf("Grade azul", "Preto", "Azul degradê", "Roxo degradê")
    val ACCENT_NAMES = arrayOf("Azul", "Verde", "Laranja", "Vermelho", "Roxo")
    val ACCENTS = intArrayOf(
        0xFF2EA8FF.toInt(), 0xFF3DDC84.toInt(), 0xFFFF9800.toInt(),
        0xFFFF4D4D.toInt(), 0xFFB388FF.toInt()
    )
    val GAUGE_NAMES = arrayOf("Arco", "Digital")
    val MARKER_NAMES = arrayOf("Seta", "Moto", "Ponto", "Círculo")
    val UNIT_NAMES = arrayOf("km/h e km", "mph e milhas")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getInt(ctx: Context, key: String): Int = prefs(ctx).getInt(key, 0)

    fun getStr(ctx: Context, key: String): String = prefs(ctx).getString(key, "") ?: ""

    fun accentColor(ctx: Context): Int =
        ACCENTS[getInt(ctx, "accent").coerceIn(0, ACCENTS.size - 1)]

    // Salva um valor e aumenta a "versão", para a tela principal saber que precisa se redesenhar
    fun save(ctx: Context, key: String, value: Any) {
        val e = prefs(ctx).edit()
        when (value) {
            is Int -> e.putInt(key, value)
            is String -> e.putString(key, value)
        }
        e.putInt("ver", getInt(ctx, "ver") + 1)
        e.apply()
    }

    fun version(ctx: Context): Int = getInt(ctx, "ver")
}
