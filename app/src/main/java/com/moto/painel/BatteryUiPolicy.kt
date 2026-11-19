package com.moto.painel

/** Estado do indicador independente de Android para testes unitarios. */
internal object BatteryUiPolicy {
    fun isCritical(percentage: Int): Boolean = percentage.coerceIn(0, 100) <= 15

    fun frameFor(percentage: Int, charging: Boolean, phase: Float): Int {
        val current = percentage.coerceIn(0, 100)
        if (isCritical(current)) {
            // Nao mostrar verde enquanto a bateria real ainda estiver critica.
            return ((current + 2) / 5).coerceIn(0, 3)
        }
        val animated = if (charging) {
            current + ((100 - current) * phase.coerceIn(0f, 1f)).toInt()
        } else current
        return ((animated + 2) / 5).coerceIn(4, 20)
    }
}
