package io.github.jellyforgedev.widgora.core.transport.http

import io.github.jellyforgedev.widgora.core.data.TransportCredentials
import io.github.jellyforgedev.widgora.core.data.TransportRequest
import io.github.jellyforgedev.widgora.core.data.TransportResult
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

/**
 * Exercises the transport against a real socket.
 *
 * The mock server speaks plain HTTP, so these tests construct the transport with cleartext
 * permitted. The default, which refuses it, has its own test below.
 */
class OkHttpTransportTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.close()
    }

    @Test
    fun aSuccessfulReadReturnsTheBody() {
        server.enqueue(MockResponse.Builder().code(200).body(SAMPLE_BODY).build())

        val result = read()

        assertTrue(result is TransportResult.Success, result.toString())
        assertEquals(SAMPLE_BODY, result.body)
    }

    @Test
    fun anEmptyBodyIsStillASuccess() {
        server.enqueue(MockResponse.Builder().code(200).body("").build())

        val result = read()

        assertTrue(result is TransportResult.Success, result.toString())
        assertEquals("", result.body)
    }

    /**
     * The key goes into the password field of HTTP basic authentication and the user name stays
     * empty, which is what the server checks. The expected header is decoded here rather than
     * produced with the same helper the implementation uses, so the test does not merely agree
     * with itself.
     */
    @Test
    fun theKeyTravelsAsTheBasicPasswordWithAnEmptyUserName() {
        server.enqueue(MockResponse.Builder().code(200).body(SAMPLE_BODY).build())

        read(credentials = TransportCredentials.Basic(username = "", password = "uk1_secret"))

        val header = server.takeRequest().headers["Authorization"]
        assertTrue(header != null && header.startsWith("Basic "), header.toString())
        val decoded = String(Base64.getDecoder().decode(header.removePrefix("Basic ")))
        assertEquals(":uk1_secret", decoded)
    }

    @Test
    fun anUnsuccessfulStatusIsReportedWithItsCode() {
        for (code in listOf(401, 403, 404, 418, 500, 503)) {
            server.enqueue(MockResponse.Builder().code(code).body("nope").build())

            val result = read()

            assertEquals(TransportResult.HttpStatus(code), result)
        }
    }

    /**
     * A redirect to another host would carry the credentials there. The transport reports the
     * redirect instead of following it, and the mock server sees exactly one request.
     */
    @Test
    fun aRedirectIsNotFollowed() {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "https://elsewhere.example.invalid/metrics")
                .build(),
        )

        val result = read()

        assertEquals(TransportResult.HttpStatus(302), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aBodyAtTheLimitIsAccepted() {
        server.enqueue(MockResponse.Builder().code(200).body("0123456789").build())

        val result = read(maxResponseBytes = 10)

        assertTrue(result is TransportResult.Success, result.toString())
        assertEquals(10, result.body.length)
    }

    @Test
    fun aBodyOneByteOverTheLimitIsTooLarge() {
        server.enqueue(MockResponse.Builder().code(200).body("01234567890").build())

        assertEquals(TransportResult.TooLarge, read(maxResponseBytes = 10))
    }

    /**
     * A chunked response carries no content length, so the limit cannot be checked from a header
     * before reading. This is the case the bounded read exists for.
     */
    @Test
    fun aChunkedBodyOverTheLimitIsTooLarge() {
        server.enqueue(
            MockResponse.Builder().code(200).chunkedBody("x".repeat(4096), 64).build(),
        )

        assertEquals(TransportResult.TooLarge, read(maxResponseBytes = 1024))
    }

    @Test
    fun aRefusedConnectionIsUnreachable() {
        val url = server.url("/metrics").toString()
        server.close()

        assertEquals(TransportResult.Unreachable, transport().read(TransportRequest(url = url)))
    }

    @Test
    fun aStalledResponseIsUnreachable() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(SAMPLE_BODY)
                .bodyDelay(5, TimeUnit.SECONDS)
                .build(),
        )

        val impatient = OkHttpClient.Builder()
            .readTimeout(150, TimeUnit.MILLISECONDS)
            .callTimeout(500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        val result = OkHttpTransport(client = impatient, cleartextAllowed = true)
            .read(TransportRequest(url = server.url("/metrics").toString()))

        assertEquals(TransportResult.Unreachable, result)
    }

    @Test
    fun plainHttpIsRefusedByDefaultAndNothingIsSent() {
        val url = server.url("/metrics").toString()

        val result = OkHttpTransport().read(TransportRequest(url = url))

        assertEquals(TransportResult.AddressRejected, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anAddressThatIsNotAnHttpUrlIsRefused() {
        val refused = listOf("not a url", "ftp://example.invalid/metrics", "/metrics", "example.invalid")
        for (url in refused) {
            assertEquals(
                TransportResult.AddressRejected,
                transport().read(TransportRequest(url = url)),
                url,
            )
        }
    }

    @Test
    fun theDocumentedTimeoutsAreTheOnesInUse() {
        assertEquals(10L, OkHttpTransport.CONNECT_TIMEOUT_SECONDS)
        assertEquals(15L, OkHttpTransport.READ_TIMEOUT_SECONDS)
        assertEquals(20L, OkHttpTransport.CALL_TIMEOUT_SECONDS)
        val client = OkHttpTransport.defaultClient()
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(15_000, client.readTimeoutMillis)
        assertEquals(20_000, client.callTimeoutMillis)
        assertEquals(false, client.followRedirects)
        assertEquals(false, client.followSslRedirects)
        assertEquals(false, client.retryOnConnectionFailure)
    }

    /**
     * The production client configuration, so that these tests exercise the timeouts, the
     * refusal to follow redirects and the absence of retries rather than OkHttp's defaults.
     * Only cleartext is permitted, because the mock server speaks plain HTTP.
     */
    private fun transport(): OkHttpTransport =
        OkHttpTransport(client = OkHttpTransport.defaultClient(), cleartextAllowed = true)

    private fun read(
        credentials: TransportCredentials? = null,
        maxResponseBytes: Long = TransportRequest.DEFAULT_MAX_RESPONSE_BYTES,
    ): TransportResult = transport().read(
        TransportRequest(
            url = server.url("/metrics").toString(),
            credentials = credentials,
            maxResponseBytes = maxResponseBytes,
        ),
    )

    private companion object {
        const val SAMPLE_BODY = "monitor_status{monitor_id=\"1\"} 1\n"
    }
}
