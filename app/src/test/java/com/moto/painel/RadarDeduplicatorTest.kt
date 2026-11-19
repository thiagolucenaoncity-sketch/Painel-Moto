package com.moto.painel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarDeduplicatorTest {
    private fun radar(
        id: String,
        lat: Double = -23.600000,
        lon: Double = -46.600000,
        kind: String = "FIXED",
        limit: String = "60",
        bearing: Float? = null
    ) = RadarDeduplicator.Record(id, lat, lon, kind, limit, bearing, "PARDAL")

    @Test fun nearbySameDirectionProducesOneAveragePosition() {
        val a = radar("r1", bearing = 91f)
        val b = radar("r2", lat = -23.600090, bearing = 92f)
        val merged = RadarDeduplicator.consolidate(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals(-23.600045, merged.single().lat, 0.0000001)
        assertEquals(-46.600000, merged.single().lon, 0.0000001)
        assertEquals("60", merged.single().limit)
        assertEquals("r1", merged.single().id)
    }

    @Test fun oppositeDirectionMustKeepSeparateRadars() {
        val a = radar("east", bearing = 90f)
        val b = radar("west", lat = -23.600070, bearing = 270f)
        assertEquals(2, RadarDeduplicator.consolidate(listOf(a, b)).size)
    }

    @Test fun noBearingNearTenMetresCanStillMerge() {
        val a = radar("one", bearing = null)
        val b = radar("two", lat = -23.600090, bearing = null)
        assertEquals(1, RadarDeduplicator.consolidate(listOf(a, b)).size)
    }

    @Test fun twoDifferentSpeedLimitsDoNotMerge() {
        val a = radar("60", limit = "60")
        val b = radar("50", lat = -23.600055, limit = "50")
        assertEquals(2, RadarDeduplicator.consolidate(listOf(a, b)).size)
    }

    @Test fun redLightCamerasAndSectionsAreNeverMerged() {
        val records = listOf(
            radar("signal1", kind = "RED_LIGHT"),
            radar("signal2", lat = -23.600040, kind = "RED_LIGHT"),
            radar("section1", kind = "SECTION"),
            radar("section2", lat = -23.600030, kind = "SECTION")
        )
        assertEquals(4, RadarDeduplicator.consolidate(records).size)
    }

    @Test fun chainOverTwentyMetresDoesNotCollapseToOne() {
        val records = listOf(
            radar("a"),
            radar("b", lat = -23.600090),
            radar("c", lat = -23.600180)
        )
        assertEquals(2, RadarDeduplicator.consolidate(records).size)
    }

    @Test fun duplicateWithUnknownSpeedRetainsKnownSpeed() {
        val records = listOf(
            radar("unknown", limit = ""),
            radar("known", lat = -23.600070, limit = "50")
        )
        val merged = RadarDeduplicator.consolidate(records)
        assertEquals(1, merged.size)
        assertEquals("50", merged.single().limit)
    }

    @Test fun distantRadarsAreUnaffected() {
        val records = listOf(
            radar("first"),
            radar("second", lat = -23.605000)
        )
        assertEquals(2, RadarDeduplicator.consolidate(records).size)
        assertTrue(RadarDeduplicator.consolidate(records).map { it.id }.contains("second"))
    }
}
