package com.moto.painel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertVisualPolicyTest {
    @Test fun distantZoomReducesClutter() {
        assertEquals(10, AlertVisualPolicy.maxVisible(12.0))
        assertEquals(20, AlertVisualPolicy.maxVisible(14.0))
        assertEquals(35, AlertVisualPolicy.maxVisible(16.0))
        assertEquals(65, AlertVisualPolicy.maxVisible(18.0))
        assertEquals(0.0, AlertVisualPolicy.radiusMeters("semaforo", "other", 16.9), 0.0)
        assertEquals(0.0, AlertVisualPolicy.radiusMeters("lombada", "other", 15.9), 0.0)
        assertEquals(0.0, AlertVisualPolicy.radiusMeters("radar", "other", 12.0), 0.0)
    }

    @Test fun nextHazardRemainsRelevantWhenZoomedOut() {
        assertEquals(2200.0, AlertVisualPolicy.radiusMeters("radar", "next", 11.0), 0.0)
        assertTrue(AlertVisualPolicy.radiusMeters("radar", "route", 13.0) >
            AlertVisualPolicy.radiusMeters("radar", "other", 13.0))
    }

    @Test fun detailsAppearOnlyWhenUseful() {
        assertFalse(AlertVisualPolicy.showSpeedSign("other", 16.0))
        assertFalse(AlertVisualPolicy.showSpeedSign("other", 17.9))
        assertTrue(AlertVisualPolicy.showSpeedSign("other", 18.0))
        assertTrue(AlertVisualPolicy.showSpeedSign("next", 12.0))
        assertTrue(AlertVisualPolicy.showSpeedSign("route", 15.0))
    }
}
