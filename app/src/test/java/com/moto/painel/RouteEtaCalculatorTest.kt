package com.moto.painel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteEtaCalculatorTest {
    private val points = listOf(
        RouteEtaCalculator.Coordinate(-23.6, -46.6),
        RouteEtaCalculator.Coordinate(-23.59, -46.6)
    )
    private val profile = RouteEtaCalculator.profile(points)

    @Test fun halfwayMeansHalfDistanceAndTime() {
        val projection = RouteEtaCalculator.project(profile, -23.595, -46.6)
        assertNotNull(projection)
        val remaining = RouteEtaCalculator.fractionRemaining(profile, projection!!.progressMeters)
        assertEquals(0.5, remaining, 0.025)
        assertEquals(5000.0, RouteEtaCalculator.distanceRemaining(10_000.0, remaining), 250.0)
        assertEquals(600.0, RouteEtaCalculator.durationRemaining(1200.0, remaining), 30.0)
    }

    @Test fun endpointMeansArrival() {
        val projection = RouteEtaCalculator.project(profile, -23.59, -46.6)!!
        assertEquals(0.0, RouteEtaCalculator.fractionRemaining(profile, projection.progressMeters), 0.001)
    }

    @Test fun offRoutePositionIsDetected() {
        val projection = RouteEtaCalculator.project(profile, -23.595, -46.596)!!
        assertTrue(projection.offRouteMeters > 100.0)
    }

    @Test fun emptyGeometryKeepsWholeEstimate() {
        val empty = RouteEtaCalculator.profile(emptyList())
        assertEquals(1.0, RouteEtaCalculator.fractionRemaining(empty, 100.0), 0.001)
        assertEquals(null, RouteEtaCalculator.project(empty, -23.6, -46.6))
    }

    @Test fun estimatesAreClampedToValidRange() {
        assertEquals(0.0, RouteEtaCalculator.distanceRemaining(1000.0, -2.0), 0.001)
        assertEquals(1000.0, RouteEtaCalculator.distanceRemaining(1000.0, 4.0), 0.001)
    }
}
