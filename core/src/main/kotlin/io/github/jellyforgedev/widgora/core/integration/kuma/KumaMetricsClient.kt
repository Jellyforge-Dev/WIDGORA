package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.core.data.Transport
import io.github.jellyforgedev.widgora.core.data.TransportCredentials
import io.github.jellyforgedev.widgora.core.data.TransportRequest
import io.github.jellyforgedev.widgora.core.data.TransportResult
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason

/**
 * Fetches one instance's metrics and hands the text to [KumaMetricsReader].
 *
 * The transport is injected, which keeps this class testable against a fake and keeps the
 * integration free of any knowledge about HTTP clients, as the module rules require.
 *
 * **Thread contract** inherited from [Transport]: the call blocks and must not run on the main
 * thread.
 */
class KumaMetricsClient(private val transport: Transport) {

    /**
     * @param baseUrl the address of the installation, with or without a trailing slash, and with
     *   or without the metrics path already appended
     * @param apiKey the key the user created in the service
     *
     * The key travels as the **password of HTTP basic authentication with an empty user name**.
     * That is what the server implements: its metrics route is guarded by a basic-authentication
     * middleware whose authorizer ignores the user name and verifies the password as an API key.
     * A query parameter would be wrong regardless, because it would end up in access logs.
     */
    fun fetch(
        baseUrl: String,
        apiKey: String,
        maxResponseBytes: Long = TransportRequest.DEFAULT_MAX_RESPONSE_BYTES,
    ): KumaReadResult {
        val request = TransportRequest(
            url = metricsUrl(baseUrl),
            credentials = TransportCredentials.Basic(username = "", password = apiKey),
            maxResponseBytes = maxResponseBytes,
        )

        return when (val result = transport.read(request)) {
            is TransportResult.Success -> KumaMetricsReader.read(result.body)
            is TransportResult.HttpStatus -> KumaReadResult.Failure(reasonFor(result.code))
            TransportResult.Unreachable -> KumaReadResult.Failure(Reason.UNREACHABLE)
            TransportResult.TooLarge -> KumaReadResult.Failure(Reason.OVERSIZED)
            TransportResult.AddressRejected -> KumaReadResult.AddressRejected
        }
    }

    /**
     * People paste the address of the page they are looking at, which is sometimes already the
     * metrics address. Appending the path a second time would produce a 404 that looks like a
     * disabled interface, so an address that already ends in the path is left alone.
     */
    private fun metricsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith(METRICS_PATH)) trimmed else trimmed + METRICS_PATH
    }

    /**
     * A 404 is kept apart from the other failures. The server answers it when its metrics
     * interface is switched off in its own settings, and it is equally what a wrong address
     * produces. Both are the user's to fix, and neither may be claimed over the other.
     */
    private fun reasonFor(code: Int): Reason = when (code) {
        401, 403 -> Reason.UNAUTHORIZED
        404 -> Reason.ENDPOINT_NOT_FOUND
        else -> Reason.MALFORMED_RESPONSE
    }

    companion object {

        const val METRICS_PATH = "/metrics"
    }
}
