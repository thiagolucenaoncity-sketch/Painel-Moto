package com.moto.painel

import android.content.Context

// Guarda as escolhas do usuário no celular
object Prefs {
    private const val FILE = "painel_prefs"

    val BG_NAMES = arrayOf("Grade azul", "Preto", "Azul degradê", "Roxo degradê")
    val GAUGE_NAMES = arrayOf("Arco", "Digital")
    val MARKER_NAMES = arrayOf("Seta Waze", "Carro", "Moto", "Ponto GPS", "Pino")
    val UNIT_NAMES = arrayOf("km/h e km", "mph e milhas")

    // Paleta usada por todas as opções de cor. Índice 0 = "Padrão"
    val COLOR_NAMES = arrayOf(
        "Padrão", "Azul", "Branco", "Verde", "Laranja", "Vermelho",
        "Roxo", "Amarelo", "Rosa", "Ciano", "Cinza", "Preto"
    )
    val COLOR_VALUES = intArrayOf(
        0,
        0xFF2EA8FF.toInt(),
        0xFFFFFFFF.toInt(),
        0xFF3DDC84.toInt(),
        0xFFFF9800.toInt(),
        0xFFFF4D4D.toInt(),
        0xFFB388FF.toInt(),
        0xFFFFC107.toInt(),
        0xFFFF4081.toInt(),
        0xFF00E5FF.toInt(),
        0xFF7F93B5.toInt(),
        0xFF000000.toInt()
    )

    // Limites de velocidade das faixas do arco (km/h)
    val LIMIT1_VALUES = intArrayOf(30, 40, 50, 60, 70, 80, 90, 100, 110)
    val LIMIT2_VALUES = intArrayOf(60, 70, 80, 90, 100, 110, 120, 130, 150)

    // Todas as chaves de cor (usadas para restaurar o padrão)
    val COLOR_KEYS = arrayOf(
        "accent_c", "bg_c", "grid_c", "card_c", "clock_c",
        "label_c", "arc_c1", "arc_c2", "arc_c3", "num_color", "unit_color", "stat_c"
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getInt(ctx: Context, key: String): Int = prefs(ctx).getInt(key, 0)

    // Valor salvo, ou o padrão se o usuário nunca mexeu nessa opção
    fun intOr(ctx: Context, key: String, default: Int): Int =
        if (prefs(ctx).contains(key)) prefs(ctx).getInt(key, default) else default

    fun getStr(ctx: Context, key: String): String = prefs(ctx).getString(key, "") ?: ""

    // Devolve a cor escolhida para a chave, ou o valor padrão se estiver em "Padrão"
    fun colorOf(ctx: Context, key: String, default: Int): Int {
        val idx = getInt(ctx, key).coerceIn(0, COLOR_VALUES.size - 1)
        return if (idx == 0) default else COLOR_VALUES[idx]
    }

    fun accentColor(ctx: Context): Int = colorOf(ctx, "accent_c", 0xFF2EA8FF.toInt())

    // Cor de cada faixa do arco: 1 = azul (destaque), 2 = amarelo, 3 = vermelho, quando "Padrão"
    fun arcZoneColor(ctx: Context, zone: Int, accent: Int): Int {
        val idx = getInt(ctx, "arc_c$zone").coerceIn(0, COLOR_VALUES.size - 1)
        if (idx != 0) return COLOR_VALUES[idx]
        return when (zone) {
            1 -> accent
            2 -> 0xFFFFC107.toInt()
            else -> 0xFFFF4D4D.toInt()
        }
    }

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

    // Salva sem mexer na versão (usado para posição do botão ♪ e estilo do mapa)
    fun putRaw(ctx: Context, key: String, value: Int) {
        prefs(ctx).edit().putInt(key, value).apply()
    }

    // Volta todas as cores para o padrão
    fun resetColors(ctx: Context) {
        val e = prefs(ctx).edit()
        COLOR_KEYS.forEach { e.putInt(it, 0) }
        e.putInt("ver", getInt(ctx, "ver") + 1)
        e.apply()
    }

    fun version(ctx: Context): Int = getInt(ctx, "ver")
}
