package com.moto.painel

/**
 * Interpreta a manobra estruturada do OSRM sem recortar frases por regex.
 * A frase extensa continua disponível para a orientação por voz.
 */
internal object ManeuverPresentation {
    fun action(type: String, modifier: String, exit: Int = 0): String = when {
        type == "arrive" -> "Chegada ao destino"
        type == "depart" -> "Início do percurso"
        modifier == "uturn" -> "Faça o retorno"
        type == "roundabout" || type == "rotary" ->
            if (exit > 0) "Na rotatória, pegue a ${exit}ª saída" else "Entre na rotatória"
        type == "merge" -> "Entre na via"
        type == "fork" -> when {
            modifier.contains("right") -> "Mantenha-se à direita"
            modifier.contains("left") -> "Mantenha-se à esquerda"
            else -> "Siga a bifurcação"
        }
        type == "off ramp" -> when {
            modifier.contains("right") -> "Pegue a saída à direita"
            modifier.contains("left") -> "Pegue a saída à esquerda"
            else -> "Pegue a saída"
        }
        type == "on ramp" -> "Acesse a via"
        modifier.contains("slight right") -> "Vire levemente à direita"
        modifier.contains("slight left") -> "Vire levemente à esquerda"
        modifier.contains("sharp right") -> "Vire à direita"
        modifier.contains("sharp left") -> "Vire à esquerda"
        modifier == "right" -> "Vire à direita"
        modifier == "left" -> "Vire à esquerda"
        type == "new name" || type == "continue" || modifier == "straight" -> "Siga em frente"
        else -> "Continue o percurso"
    }

    fun roadTitle(road: String, type: String, action: String): String = when {
        type == "arrive" -> "Destino"
        road.isNotBlank() -> road.trim()
        else -> action
    }
}
