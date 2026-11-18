package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.core.transport.http.OkHttpTransport
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * A hand probe against a real installation. It is **not** part of the automated suite.
 *
 * It runs only when both environment variables below carry a value, and is reported as skipped
 * otherwise, so that an ordinary build neither depends on a reachable server nor fails without
 * one. The variables are supplied by the person running it; nothing in this repository sets
 * them, and no value of theirs is written to a report.
 *
 * What it asserts is deliberately thin: that a real response arrives and describes at least one
 * monitor. Everything about the shape of that response is already covered by the tests that run
 * against the captured sample, which do not need a network.
 *
 * It prints no address, no key, no monitor name and no part of the response. On failure it names
 * only the classified reason, because that is what a person needs in order to act and it carries
 * nothing from the server.
 */
class KumaLiveInstanceTest {

    @Test
    fun theConfiguredInstanceAnswersWithAtLeastOneMonitor() {
        val baseUrl = System.getenv(URL_VARIABLE).orEmpty()
        val apiKey = System.getenv(KEY_VARIABLE).orEmpty()
        assumeTrue(
            baseUrl.isNotBlank() && apiKey.isNotBlank(),
            "skipped: set " + URL_VARIABLE + " and " + KEY_VARIABLE + " to run this probe",
        )

        val result = KumaMetricsClient(OkHttpTransport()).fetch(baseUrl, apiKey)

        assertTrue(result is KumaReadResult.Success, describe(result))
        assertTrue(result.monitors.isNotEmpty(), "the response described no monitor")
    }

    /** Names the outcome without quoting anything the server sent. */
    private fun describe(result: KumaReadResult): String = when (result) {
        is KumaReadResult.Success -> "success"
        is KumaReadResult.Failure -> "failed, classified as " + result.reason.name
        KumaReadResult.AddressRejected -> "the address was refused before sending; https is required"
    }

    private companion object {
        const val URL_VARIABLE = "WIDGORA_KUMA_URL"
        const val KEY_VARIABLE = "WIDGORA_KUMA_KEY"
    }
}
