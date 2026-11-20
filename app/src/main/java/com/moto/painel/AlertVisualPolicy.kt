package com.moto.painel

/**
 * Filtro exclusivamente VISUAL. Não altera a base de radares, a navegação,
 * os avisos sonoros ou a verificação de direção dos alertas.
 */
internal object AlertVisualPolicy {
    fun maxVisible(zoom: Double): Int = when {
        zoom < 13.0 -> 10
        zoom < 15.0 -> 20
        zoom < 17.0 -> 35
        else -> 65
    }

    fun radiusMeters(type: String, priority: String, zoom: Double): Double =
        when (priority) {
            "next" -> 2200.0
            "route" -> when (type) {
                "radar" -> 1500.0
                "lombada" -> if (zoom >= 15.0) 550.0 else 0.0
                "semaforo" -> if (zoom >= 17.0) 400.0 else 0.0
                else -> 0.0
            }
            else -> when (type) {
                "radar" -> when {
                    zoom < 13.0 -> 0.0
                    zoom < 15.0 -> 250.0
                    zoom < 17.0 -> 450.0
                    else -> 700.0
                }
                "lombada" -> when {
                    zoom < 16.0 -> 0.0
                    zoom < 18.0 -> 200.0
                    else -> 350.0
                }
                "semaforo" -> if (zoom >= 17.0) 260.0 else 0.0
                else -> 0.0
            }
        }

    fun showSpeedSign(priority: String, zoom: Double): Boolean =
        priority != "other" || zoom >= 18.0
}
