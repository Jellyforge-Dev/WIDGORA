package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Turns one metrics response into monitors, or into a reason why it cannot be used.
 *
 * Reading is strict: an unaccountable line, a monitor metric without an identifier, a missing
 * status for a monitor that the response otherwise describes, or an uptime sample without its
 * window all end the read. The alternative, skipping what cannot be understood, would let a
 * damaged response look like a smaller but healthy installation.
 *
 * Families that are not listed here are ignored rather than rejected, so that a future addition
 * to the endpoint does not break a working installation.
 */
object KumaMetricsReader {

    /**
     * Upper bound on the text this reader accepts. The transport layer will apply its own byte
     * limit later; this one protects the parser itself. For scale: a response describing 35
     * monitors is roughly 80 thousand characters.
     */
    const val MAX_INPUT_CHARS = 1_048_576

    const val MAX_MONITORS = 2_000

    private const val MONITOR_PREFIX = "monitor_"
    private const val STATUS = "monitor_status"
    private const val RESPONSE_TIME = "monitor_response_time"
    private const val UPTIME_RATIO = "monitor_uptime_ratio"
    private const val CERT_IS_VALID = "monitor_cert_is_valid"
    private const val CERT_DAYS_REMAINING = "monitor_cert_days_remaining"

    private const val LABEL_ID = "monitor_id"
    private const val LABEL_NAME = "monitor_name"
    private const val LABEL_TYPE = "monitor_type"
    private const val LABEL_WINDOW = "window"

    /** The endpoint reports a missing value as this string rather than omitting the label. */
    private const val ABSENT_VALUE = "null"

    /** The endpoint uses this response time when a monitor produced no numeric ping. */
    private const val NO_RESPONSE_TIME = -1.0

    fun read(text: String): KumaReadResult {
        if (text.length > MAX_INPUT_CHARS) return KumaReadResult.Failure(Reason.OVERSIZED)

        val collected = LinkedHashMap<String, MonitorAccumulator>()
        try {
            for (line in text.lineSequence()) {
                val metric = MetricsTextParser.parseLine(line) ?: continue
                if (!metric.name.startsWith(MONITOR_PREFIX)) continue
                if (!isKnownFamily(metric.name)) continue

                val monitorId = metric.labels[LABEL_ID]
                if (monitorId.isNullOrEmpty() || monitorId == ABSENT_VALUE) {
                    throw MalformedMetricsException("monitor metric without an identifier")
                }
                if (!collected.containsKey(monitorId) && collected.size >= MAX_MONITORS) {
                    return KumaReadResult.Failure(Reason.OVERSIZED)
                }
                collected.getOrPut(monitorId) { MonitorAccumulator(monitorId) }.accept(metric)
            }
        } catch (malformed: MalformedMetricsException) {
            // The message names the offending construct and is deliberately not carried any
            // further: nothing outside this reader may depend on the wording, and text that a
            // server produced never reaches the user.
            return KumaReadResult.Failure(Reason.MALFORMED_RESPONSE)
        }

        if (collected.isEmpty()) return KumaReadResult.Failure(Reason.MALFORMED_RESPONSE)
        val monitors = collected.values.map { accumulator ->
            accumulator.build() ?: return KumaReadResult.Failure(Reason.MALFORMED_RESPONSE)
        }
        return KumaReadResult.Success(monitors)
    }

    private fun isKnownFamily(name: String): Boolean = name == STATUS ||
        name == RESPONSE_TIME ||
        name == UPTIME_RATIO ||
        name == CERT_IS_VALID ||
        name == CERT_DAYS_REMAINING

    private class MonitorAccumulator(private val monitorId: String) {

        private var statusSeen = false
        private var status = KumaMonitorStatus.UNKNOWN
        private var name: String? = null
        private var type: String? = null
        private var responseTimeMs: Long? = null
        private var certificateValid: Boolean? = null
        private var certificateDaysRemaining: Int? = null
        private val uptimeRatioByWindow = LinkedHashMap<String, Double>()

        fun accept(metric: MetricLine) {
            when (metric.name) {
                STATUS -> {
                    if (statusSeen) {
                        throw MalformedMetricsException("two status samples for one monitor")
                    }
                    statusSeen = true
                    status = KumaMonitorStatus.fromWireValue(metric.value)
                    name = metric.labels[LABEL_NAME]?.takeUnless { it == ABSENT_VALUE }
                    type = metric.labels[LABEL_TYPE]?.takeUnless { it == ABSENT_VALUE }
                }

                RESPONSE_TIME -> {
                    responseTimeMs = when {
                        metric.value == NO_RESPONSE_TIME -> null
                        metric.value.isNaN() -> null
                        metric.value.isInfinite() -> null
                        metric.value < 0.0 -> null
                        else -> metric.value.roundToLong()
                    }
                }

                UPTIME_RATIO -> {
                    val window = metric.labels[LABEL_WINDOW]
                    if (window.isNullOrEmpty()) {
                        throw MalformedMetricsException("uptime sample without a window")
                    }
                    uptimeRatioByWindow[window] = metric.value
                }

                CERT_IS_VALID -> {
                    certificateValid = when (metric.value) {
                        1.0 -> true
                        0.0 -> false
                        else -> null
                    }
                }

                CERT_DAYS_REMAINING -> {
                    certificateDaysRemaining = when {
                        metric.value.isNaN() || metric.value.isInfinite() -> null
                        else -> metric.value.roundToInt()
                    }
                }
            }
        }

        /** @return the monitor, or `null` if the response never stated its status. */
        fun build(): KumaMonitor? {
            if (!statusSeen) return null
            return KumaMonitor(
                monitorId = monitorId,
                name = name,
                type = type,
                status = status,
                responseTimeMs = responseTimeMs,
                uptimeRatioByWindow = uptimeRatioByWindow.toMap(),
                certificateValid = certificateValid,
                certificateDaysRemaining = certificateDaysRemaining,
            )
        }
    }
}
