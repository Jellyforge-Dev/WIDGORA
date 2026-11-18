package io.github.jellyforgedev.widgora.settings

import io.github.jellyforgedev.widgora.core.integration.kuma.KumaMetricsClient
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaReadResult
import io.github.jellyforgedev.widgora.core.transport.http.OkHttpTransport
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What one attempt against the entered values produced. */
sealed interface ConnectionTestResult {

    /**
     * The service answered. Only the number of monitors is carried: a monitor name may contain
     * an internal host name, and nothing on this screen needs one.
     */
    data class Reachable(val monitorCount: Int) : ConnectionTestResult

    data class Failed(val reason: Reason) : ConnectionTestResult

    /** The address was refused before anything was sent. */
    data object AddressRejected : ConnectionTestResult
}

/** Tries the entered values without saving them. */
interface ConnectionTester {

    suspend fun test(baseUrl: String, apiKey: String): ConnectionTestResult
}

/**
 * The real test, wired to the metrics client and the HTTP transport.
 *
 * Wiring a transport to an integration is this module's job, which is why the assembly happens
 * here rather than inside the integration.
 *
 * The call is moved off the calling thread explicitly. The transport blocks by contract, and a
 * blocked main thread on a television is a frozen picture.
 */
class KumaConnectionTester(
    private val client: KumaMetricsClient = KumaMetricsClient(OkHttpTransport()),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConnectionTester {

    override suspend fun test(baseUrl: String, apiKey: String): ConnectionTestResult =
        withContext(dispatcher) {
            when (val result = client.fetch(baseUrl, apiKey)) {
                is KumaReadResult.Success -> ConnectionTestResult.Reachable(result.monitors.size)
                is KumaReadResult.Failure -> ConnectionTestResult.Failed(result.reason)
                KumaReadResult.AddressRejected -> ConnectionTestResult.AddressRejected
            }
        }
}
