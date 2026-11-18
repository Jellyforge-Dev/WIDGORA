package io.github.jellyforgedev.widgora.core.integration.kuma

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KumaMonitorStatusTest {

    @Test
    fun mapsTheFourDocumentedValues() {
        assertEquals(KumaMonitorStatus.DOWN, KumaMonitorStatus.fromWireValue(0.0))
        assertEquals(KumaMonitorStatus.UP, KumaMonitorStatus.fromWireValue(1.0))
        assertEquals(KumaMonitorStatus.PENDING, KumaMonitorStatus.fromWireValue(2.0))
        assertEquals(KumaMonitorStatus.MAINTENANCE, KumaMonitorStatus.fromWireValue(3.0))
    }

    @Test
    fun anyOtherValueIsUnknown() {
        assertEquals(KumaMonitorStatus.UNKNOWN, KumaMonitorStatus.fromWireValue(4.0))
        assertEquals(KumaMonitorStatus.UNKNOWN, KumaMonitorStatus.fromWireValue(-1.0))
        assertEquals(KumaMonitorStatus.UNKNOWN, KumaMonitorStatus.fromWireValue(1.5))
        assertEquals(KumaMonitorStatus.UNKNOWN, KumaMonitorStatus.fromWireValue(Double.NaN))
        assertEquals(
            KumaMonitorStatus.UNKNOWN,
            KumaMonitorStatus.fromWireValue(Double.POSITIVE_INFINITY),
        )
    }

    /** The rule that matters: an unrecognised state never reads as a working one. */
    @Test
    fun onlyUpCountsAsUp() {
        assertTrue(KumaMonitorStatus.UP.countsAsUp)
        assertFalse(KumaMonitorStatus.UNKNOWN.countsAsUp)
        assertFalse(KumaMonitorStatus.PENDING.countsAsUp)
        assertFalse(KumaMonitorStatus.MAINTENANCE.countsAsUp)
        assertFalse(KumaMonitorStatus.DOWN.countsAsUp)
    }

    @Test
    fun onlyDownCountsAsDown() {
        assertTrue(KumaMonitorStatus.DOWN.countsAsDown)
        assertFalse(KumaMonitorStatus.UNKNOWN.countsAsDown)
        assertFalse(KumaMonitorStatus.PENDING.countsAsDown)
        assertFalse(KumaMonitorStatus.MAINTENANCE.countsAsDown)
        assertFalse(KumaMonitorStatus.UP.countsAsDown)
    }

    @Test
    fun theStateSetIsFixedAtFive() {
        assertEquals(5, KumaMonitorStatus.entries.size)
    }
}
