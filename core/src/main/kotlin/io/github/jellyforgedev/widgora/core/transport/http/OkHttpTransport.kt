package io.github.jellyforgedev.widgora.core.transport.http

import io.github.jellyforgedev.widgora.core.data.Transport
import io.github.jellyforgedev.widgora.core.data.TransportCredentials
import io.github.jellyforgedev.widgora.core.data.TransportRequest
import io.github.jellyforgedev.widgora.core.data.TransportResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The HTTP transport, built on OkHttp.
 *
 * Everything protocol-specific stops here. The rest of the project sees [Transport] only.
 *
 * @param cleartextAllowed whether a plain `http://` address may be read. The default refuses it.
 *   Android itself blocks cleartext for applications targeting API 28 and above unless the
 *   network security configuration opts in, and this application does not. The parameter exists
 *   because SECURITY.md foresees a later step in which the user may permit cleartext for one
 *   instance deliberately; until that step exists, only tests against a local mock server set it.
 */
class OkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
    private val cleartextAllowed: Boolean = false,
) : Transport {

    override fun read(request: TransportRequest): TransportResult {
        val url = request.url.toHttpUrlOrNull() ?: return TransportResult.AddressRejected
        if (!url.isHttps && !cleartextAllowed) return TransportResult.AddressRejected

        val builder = Request.Builder().url(url).get()
        when (val credentials = request.credentials) {
            is TransportCredentials.Basic -> builder.header(
                AUTHORIZATION_HEADER,
                Credentials.basic(credentials.username, credentials.password),
            )

            null -> Unit
        }

        return try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    TransportResult.HttpStatus(response.code)
                } else {
                    readBounded(response, request.maxResponseBytes)
                }
            }
        } catch (unreachable: IOException) {
            // Timeouts, refused connections, unresolved names and failed handshakes all arrive
            // here. The exception message is not carried any further: it names hosts and ports.
            TransportResult.Unreachable
        }
    }

    /**
     * Reads the body with a hard upper bound.
     *
     * The limit is applied while reading rather than afterwards, and `Content-Length` is not
     * trusted for it: a chunked response does not carry one, and a server is free to understate
     * it. Asking the source for one byte more than the limit buffers at most that much and
     * answers whether there is more to come.
     */
    private fun readBounded(response: Response, maxBytes: Long): TransportResult {
        val source = response.body.source()
        if (source.request(maxBytes + 1)) return TransportResult.TooLarge
        return TransportResult.Success(source.readUtf8())
    }

    companion object {

        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 15L
        const val CALL_TIMEOUT_SECONDS = 20L

        private const val AUTHORIZATION_HEADER = "Authorization"

        /**
         * Redirects are not followed: a redirect to another host would carry the credentials of
         * this request to that host. Connection failures are not retried either; a failed read
         * is reported rather than quietly repeated, and repetition belongs to the refresh
         * schedule, which can reason about intervals.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
