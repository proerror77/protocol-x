package com.mandonothing.ops

import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyLinkDiagnosticsTest {
    @Test
    fun countsAPeerOnceAndPreservesAnUnknownDeliveryRoute() {
        val diagnostics = NearbyLinkDiagnostics()
        diagnostics.observe("aw", NearbyRadio.WIFI, "A", false, null, 1)
        diagnostics.observe("ab", NearbyRadio.BLE, "A", true, null, 2)
        diagnostics.observe("b", NearbyRadio.BLE, "B", false, null, 3)
        assertEquals(NearbyDiagnosticSnapshot(1, 2, 2, 3, 2), diagnostics.snapshot())
        diagnostics.remove("ab")
        diagnostics.observe("aw", NearbyRadio.WIFI, "A", true, NearbyRadio.BLE, 4, supportsLive = true)
        assertEquals(NearbyDiagnosticSnapshot(1, 1, 2, 4, 4, NearbyRadio.BLE, 1), diagnostics.snapshot())
    }
}
