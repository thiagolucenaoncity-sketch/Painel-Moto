package com.moto.painel

/**
 * Regra única dos sons de alertas: só radares adiante, na rota em navegação.
 * Lombadas e semáforos seguem visíveis no mapa, mas nunca geram som.
 */
internal object RadarSoundPolicy {
    fun shouldNotify(
        kind: String,
        navigating: Boolean,
        routePointCount: Int,
        hazardProgress: Double?,
        userProgress: Double
    ): Boolean =
        kind == "radar" &&
            navigating &&
            routePointCount > 1 &&
            hazardProgress != null &&
            hazardProgress > userProgress + 8.0
}
