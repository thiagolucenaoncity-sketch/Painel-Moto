package com.moto.painel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarSoundPolicyTest {
    private fun allowed(kind: String = "radar", navigating: Boolean = true,
                        routePointCount: Int = 3, hazardProgress: Double? = 180.0,
                        userProgress: Double = 100.0) =
        RadarSoundPolicy.shouldNotify(kind, navigating, routePointCount, hazardProgress, userProgress)

    @Test fun radarAheadOnActiveRouteMayNotify() {
        assertTrue(allowed())
    }

    @Test fun radarWithoutRouteNeverNotifies() {
        assertFalse(allowed(navigating = false))
        assertFalse(allowed(routePointCount = 0))
        assertFalse(allowed(routePointCount = 1))
    }

    @Test fun offRouteRadarDoesNotNotify() {
        assertFalse(allowed(hazardProgress = null))
    }

    @Test fun radarBehindOrAlreadyPassedDoesNotNotify() {
        assertFalse(allowed(hazardProgress = 100.0))
        assertFalse(allowed(hazardProgress = 106.0))
        assertFalse(allowed(hazardProgress = 108.0))
    }

    @Test fun bumpAndTrafficLightNeverNotify() {
        assertFalse(allowed(kind = "lombada"))
        assertFalse(allowed(kind = "semaforo"))
    }
}
