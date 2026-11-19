package com.moto.painel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoadSpeedLimitsTest {
    private fun road(lat: Double, offset: Double, kmh: Int) =
        RoadSpeedLimits.Segment(lat, -46.6100, lat, -46.6000 + offset, kmh)

    @Test fun parsesOnlyExplicitNumericLimits() {
        assertEquals(50, RoadSpeedLimits.parseKmh("50"))
        assertEquals(60, RoadSpeedLimits.parseKmh("60 km/h"))
        assertEquals(40, RoadSpeedLimits.parseKmh("40 kph"))
        assertNull(RoadSpeedLimits.parseKmh("BR:urban"))
        assertNull(RoadSpeedLimits.parseKmh("signals"))
        assertNull(RoadSpeedLimits.parseKmh("50 mph"))
        assertNull(RoadSpeedLimits.parseKmh("50;60"))
        assertNull(RoadSpeedLimits.parseKmh(""))
    }

    @Test fun nearbyStraightWayShowsItsLimit() {
        val values = listOf(road(-23.60000, 0.0, 50))
        assertEquals(50, RoadSpeedLimits.nearest(-23.60002, -46.6050, 90f, values))
    }

    @Test fun roadTooFarAwayDoesNotPretendToKnowSpeed() {
        val values = listOf(road(-23.60000, 0.0, 50))
        assertNull(RoadSpeedLimits.nearest(-23.6030, -46.6050, 90f, values))
    }

    @Test fun conflictingParallelWaysAreAmbiguous() {
        val values = listOf(
            road(-23.60000, 0.0, 50),
            road(-23.60006, 0.0, 60)
        )
        assertNull(RoadSpeedLimits.nearest(-23.60003, -46.6050, 90f, values))
    }

    @Test fun crossingRoadWithDifferentHeadingIsIgnoredWhenDriving() {
        val east = road(-23.60000, 0.0, 50)
        val north = RoadSpeedLimits.Segment(-23.602, -46.605, -23.598, -46.605, 30)
        assertEquals(50, RoadSpeedLimits.nearest(-23.60000, -46.6050, 90f, listOf(east, north)))
        // Without GPS heading, the intersection is ambiguous: no assumed limit.
        assertNull(RoadSpeedLimits.nearest(-23.60000, -46.6050, null, listOf(east, north)))
    }
}
