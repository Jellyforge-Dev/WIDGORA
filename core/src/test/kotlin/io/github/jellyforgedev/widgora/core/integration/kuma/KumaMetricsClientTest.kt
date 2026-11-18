package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.core.data.Transport
import io.github.jellyforgedev.widgora.core.data.TransportCredentials
import io.github.jellyforgedev.widgora.core.data.TransportRequest
import io.github.jellyforgedev.widgora.core.data.TransportResult
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The client against a fake transport. No socket is involved: what is tested here is the address
 * it builds, the credentials it sends, and how it translates an outcome into a display reason.
 */
class KumaMetricsClientTest {

    @Test
    fun theMetricsPathIsAppendedToTheAddress() {
        assertEquals("https://kuma.example.invalid/metrics", urlFor("https://kuma.example.invalid"))
    }

    @Test
    fun aTrailingSlashDoesNotProduceADoubleSlash() {
        assertEquals("https://kuma.example.invalid/metrics", urlFor("https://kuma.example.invalid/"))
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertEquals(
            "https://kuma.example.invalid/metrics",
            urlFor("  https://kuma.example.invalid  "),
        )
    }

    /** Pasting the metrics address itself must not produce the path twice. */
    @Test
    fun anAddressThatAlreadyEndsInThePathIsLeftAlone() {
        assertEquals(
            "https://kuma.example.invalid/metrics",
            urlFor("https://kuma.example.invalid/metrics"),
        )
        assertEquals(
            "https://kuma.example.invalid/metrics",
            urlFor("https://kuma.example.invalid/metrics/"),
        )
    }

    @Test
    fun aSubPathIsKept() {
        assertEquals(
            "https://host.example.invalid/kuma/metrics",
            urlFor("https://host.example.invalid/kuma"),
        )
    }

    @Test
    fun theKeyIsSentAsTheBasicPasswordWithAnEmptyUserName() {
        val transport = FakeTransport(TransportResult.Success(ONE_MONITOR))

        KumaMetricsClient(transport).fetch(BASE_URL, "uk1_secret")

        assertEquals(
            TransportCredentials.Basic(username = "", password = "uk1_secret"),
            transport.lastRequest?.credentials,
        )
    }

    @Test
    fun theResponseLimitIsPassedToTheTransport() {
        val explicit = FakeTransport(TransportResult.Success(ONE_MONITOR))
        KumaMetricsClient(explicit).fetch(BASE_URL, KEY, maxResponseBytes = 4096)
        assertEquals(4096L, explicit.lastRequest?.maxResponseBytes)

        val default = FakeTransport(TransportResult.Success(ONE_MONITOR))
        KumaMetricsClient(default).fetch(BASE_URL, KEY)
        assertEquals(
            TransportRequest.DEFAULT_MAX_RESPONSE_BYTES,
            default.lastRequest?.maxResponseBytes,
        )
    }

    @Test
    fun aSuccessfulResponseIsParsed() {
        val result = fetch(TransportResult.Success(ONE_MONITOR))

        assertTrue(result is KumaReadResult.Success, result.toString())
        assertEquals(1, result.monitors.size)
        assertEquals(KumaMonitorStatus.UP, result.monitors.single().status)
    }

    @Test
    fun anUnparsableBodyIsAMalformedResponse() {
        assertEquals(
            Reason.MALFORMED_RESPONSE,
            reasonOf(TransportResult.Success("<html>nope</html>")),
        )
    }

    @Test
    fun aRejectedKeyIsUnauthorized() {
        assertEquals(Reason.UNAUTHORIZED, reasonOf(TransportResult.HttpStatus(401)))
        assertEquals(Reason.UNAUTHORIZED, reasonOf(TransportResult.HttpStatus(403)))
    }

    /**
     * The server answers 404 both when its metrics interface is switched off and when the
     * address is wrong. It gets its own reason because it is the user's to fix.
     */
    @Test
    fun aMissingEndpointHasItsOwnReason() {
        assertEquals(Reason.ENDPOINT_NOT_FOUND, reasonOf(TransportResult.HttpStatus(404)))
    }

    @Test
    fun anyOtherStatusIsAMalformedResponse() {
        for (code in listOf(302, 418, 500, 502, 503)) {
            assertEquals(
                Reason.MALFORMED_RESPONSE,
                reasonOf(TransportResult.HttpStatus(code)),
                code.toString(),
            )
        }
    }

    @Test
    fun anUnreachableServiceKeepsItsOwnReason() {
        assertEquals(Reason.UNREACHABLE, reasonOf(TransportResult.Unreachable))
    }

    @Test
    fun anOversizedResponseIsOversized() {
        assertEquals(Reason.OVERSIZED, reasonOf(TransportResult.TooLarge))
    }

    /** A refused address is not a display reason: nothing was attempted. */
    @Test
    fun aRefusedAddressIsReportedSeparately() {
        assertEquals(KumaReadResult.AddressRejected, fetch(TransportResult.AddressRejected))
    }

    private fun urlFor(baseUrl: String): String {
        val transport = FakeTransport(TransportResult.Success(ONE_MONITOR))
        KumaMetricsClient(transport).fetch(baseUrl, KEY)
        return transport.lastRequest?.url.orEmpty()
    }

    private fun fetch(result: TransportResult): KumaReadResult =
        KumaMetricsClient(FakeTransport(result)).fetch(BASE_URL, KEY)

    private fun reasonOf(result: TransportResult): Reason {
        val outcome = fetch(result)
        assertTrue(outcome is KumaReadResult.Failure, outcome.toString())
        return outcome.reason
    }

    private class FakeTransport(private val result: TransportResult) : Transport {

        var lastRequest: TransportRequest? = null

        override fun read(request: TransportRequest): TransportResult {
            lastRequest = request
            return result
        }
    }

    private companion object {
        const val BASE_URL = "https://kuma.example.invalid"
        const val KEY = "uk1_key"
        const val ONE_MONITOR = "monitor_status{monitor_id=\"1\",monitor_name=\"Service 01\"} 1\n"
    }
}
