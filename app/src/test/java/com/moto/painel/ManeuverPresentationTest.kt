package com.moto.painel

import org.junit.Assert.assertEquals
import org.junit.Test

class ManeuverPresentationTest {
    @Test fun directionsUseShortActionAndRealStreetName() {
        assertEquals("Vire à direita", ManeuverPresentation.action("end of road", "right"))
        assertEquals("Av. Eng. Luís Carlos Berrini", ManeuverPresentation.roadTitle(
            "Av. Eng. Luís Carlos Berrini", "turn", "Vire à direita"))
        assertEquals("Vire à esquerda", ManeuverPresentation.action("turn", "left"))
        assertEquals("Faça o retorno", ManeuverPresentation.action("turn", "uturn"))
    }

    @Test fun roundaboutAndUnknownStreetHaveSafeLabels() {
        assertEquals("Na rotatória, pegue a 2ª saída",
            ManeuverPresentation.action("roundabout", "right", 2))
        assertEquals("Siga em frente",
            ManeuverPresentation.roadTitle("", "turn", "Siga em frente"))
        assertEquals("Destino",
            ManeuverPresentation.roadTitle("Rua qualquer", "arrive", "Chegada ao destino"))
    }
}
