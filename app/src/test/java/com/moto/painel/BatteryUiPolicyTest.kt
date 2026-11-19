package com.moto.painel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryUiPolicyTest {
    @Test fun thresholdIsInclusive() {
        assertTrue(BatteryUiPolicy.isCritical(15))
        assertTrue(BatteryUiPolicy.isCritical(0))
        assertFalse(BatteryUiPolicy.isCritical(16))
    }

    @Test fun normalModeTracksLevel() {
        assertEquals(12, BatteryUiPolicy.frameFor(60, false, 0f))
        assertEquals(12, BatteryUiPolicy.frameFor(60, false, 0.9f))
    }

    @Test fun chargingFramesAnimateWithoutChangingReportedCharge() {
        assertEquals(10, BatteryUiPolicy.frameFor(50, true, 0f))
        assertEquals(20, BatteryUiPolicy.frameFor(50, true, 1f))
    }

    @Test fun criticalBatteryStaysRedWhileCharging() {
        assertEquals(3, BatteryUiPolicy.frameFor(15, true, 1f))
        assertEquals(3, BatteryUiPolicy.frameFor(15, false, 0f))
    }
}
