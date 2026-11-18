package io.github.jellyforgedev.widgora.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TransportTest {

    @Test
    fun aRequestNeedsAnAddress() {
        assertFailsWith<IllegalArgumentException> { TransportRequest(url = "") }
        assertFailsWith<IllegalArgumentException> { TransportRequest(url = "   ") }
    }

    @Test
    fun aRequestNeedsAPositiveLimit() {
        assertFailsWith<IllegalArgumentException> {
            TransportRequest(url = "https://example.invalid/metrics", maxResponseBytes = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            TransportRequest(url = "https://example.invalid/metrics", maxResponseBytes = -1)
        }
    }

    @Test
    fun theDefaultLimitIsTwoMebibytes() {
        assertEquals(2L * 1024 * 1024, TransportRequest.DEFAULT_MAX_RESPONSE_BYTES)
    }

    /**
     * A credential or a response body printed by a generated `toString` would reach every log
     * and crash report that formats the value. These overrides are the reason the types are not
     * plain data holders.
     */
    @Test
    fun aPasswordIsNotPrinted() {
        val credentials = TransportCredentials.Basic(username = "", password = "uk1_secret")
        assertFalse(credentials.toString().contains("uk1_secret"), credentials.toString())
    }

    @Test
    fun aPasswordIsNotPrintedThroughTheRequestEither() {
        val request = TransportRequest(
            url = "https://example.invalid/metrics",
            credentials = TransportCredentials.Basic(username = "", password = "uk1_secret"),
        )
        assertFalse(request.toString().contains("uk1_secret"), request.toString())
    }

    @Test
    fun aResponseBodyIsNotPrinted() {
        val result = TransportResult.Success("monitor_status{monitor_name=\"Service 01\"} 1")
        assertFalse(result.toString().contains("Service 01"), result.toString())
        assertFalse(result.toString().contains("monitor_status"), result.toString())
    }

    @Test
    fun theBodyIsStillAvailableToTheCaller() {
        assertEquals("body", TransportResult.Success("body").body)
    }
}
