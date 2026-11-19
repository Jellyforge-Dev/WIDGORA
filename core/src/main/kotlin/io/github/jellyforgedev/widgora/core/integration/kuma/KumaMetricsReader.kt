package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Turns one metrics response into monitors, or into a reason why it cannot be used.
 *
 * Reading is strict about **structure**: an unaccountable line, a monitor metric without an
 * identifier, a missing status for a monitor that the response otherwise describes, or an uptime
 * sample without its window all end the read. The alternative, skipping what cannot be understood,
 * would let a damaged response look like a smaller but healthy installation.
 *
 * Families that are not listed here are ignored rather than rejected, so that a future addition
 * to the endpoint does not break a working installation.
 *
 * ### Repeated rows for one monitor
 *
 * The endpoint can describe the same `monitor_id` twice. Observed against a real installation
 * `[V]`: after a monitor is edited, the row carrying its **previous** label values stays in the
 * response until the service is restarted, in every family at once. Four edited monitors produced
 * four duplicated identifiers across `monitor_status`, `monitor_response_time` and
 * `monitor_uptime_ratio`, and the reader of the time rejected the entire response as malformed —
 * so the dashboard reported "response not readable" for all of the installation's monitors
 * because four of them had been renamed.
 *
 * Repetition is therefore not treated as damage. Each family is reconciled on its own:
 *
 * - **Status**: agreeing rows give that status. Rows that contradict each other give
 *   [KumaMonitorStatus.UNKNOWN] — the one value that is visible as a state of its own and never
 *   counts as working. Guessing either way would be the mistake that matters, because a monitor
 *   silently counted as up is an outage nobody is shown. A contradiction is recorded in
 *   [KumaMonitor.statusContradicted], because the user is owed the difference between "the source
 *   contradicted itself" and "this version does not know that state".
 * - **Response time, uptime ratio per window, certificate**: agreeing rows are used, contradicting
 *   ones are dropped for that monitor, and in the case of the uptime ratio only for the window
 *   that disagrees. A number that might be either of two values is worse than no number.
 * - **Name and type**: the smallest candidate by Unicode code point. The reason is determinism
 *   that does not depend on the order of the response: the exposition format guarantees no order,
 *   so "the last row wins" could display a different name after every retrieval with nothing
 *   having changed on the server. A name flickering between two values looks like a defect in
 *   WIDGORA; one that is a rename behind reality does not, and the state beside it — the part that
 *   matters — is unaffected either way. The cost is stated honestly: after a rename, the older
 *   name may be the one displayed until the service restarts.
 *
 * An absent label is not a contradiction. The endpoint writes the string `null` where it has no
 * value, which says nothing about what another row for the same monitor states, so it is simply
 * not a candidate rather than a competing one.
 *
 * The stale row carries more than stale labels. Observed on the emulator against the same real
 * installation `[V]`: the four edited monitors were reported as unknown while the service's own
 * interface showed all four as up, and a restart of the service made them up again. Under the rule
 * above, unknown for exactly those four means their status rows held **different** values — so the
 * row that is left behind keeps the state the monitor had when it was edited, not only its former
 * name. A contradiction is therefore the ordinary outcome of an edit rather than a rare one, which
 * is why it is worth explaining to the user instead of merely surviving.
 *
 * What remains malformed is unchanged: a line that cannot be parsed, a response with no
 * `monitor_status` family at all, a monitor the response describes without ever stating its
 * status, and an uptime sample with no window.
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

    /**
     * Collects every row that names one monitor, then reconciles them.
     *
     * Each family keeps the **distinct** values it was told, not the latest one, because only a
     * set can tell "said twice, consistently" from "said twice, differently". A set of one is an
     * agreement whether it was stated once or ten times; a set of more than one is a
     * contradiction, which each family answers in its own way.
     */
    private class MonitorAccumulator(private val monitorId: String) {

        private val statuses = LinkedHashSet<KumaMonitorStatus>()
        private val names = LinkedHashSet<String>()
        private val types = LinkedHashSet<String>()
        private val responseTimes = LinkedHashSet<Long?>()
        private val certificateValidities = LinkedHashSet<Boolean?>()
        private val certificateDays = LinkedHashSet<Int?>()
        private val uptimeRatiosByWindow = LinkedHashMap<String, LinkedHashSet<Double>>()

        fun accept(metric: MetricLine) {
            when (metric.name) {
                STATUS -> {
                    statuses += KumaMonitorStatus.fromWireValue(metric.value)
                    metric.labels[LABEL_NAME]?.takeUnless { it == ABSENT_VALUE }
                        ?.let { names += it }
                    metric.labels[LABEL_TYPE]?.takeUnless { it == ABSENT_VALUE }
                        ?.let { types += it }
                }

                RESPONSE_TIME -> {
                    responseTimes += when {
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
                    uptimeRatiosByWindow.getOrPut(window) { LinkedHashSet() } += metric.value
                }

                CERT_IS_VALID -> {
                    certificateValidities += when (metric.value) {
                        1.0 -> true
                        0.0 -> false
                        else -> null
                    }
                }

                CERT_DAYS_REMAINING -> {
                    certificateDays += when {
                        metric.value.isNaN() || metric.value.isInfinite() -> null
                        else -> metric.value.roundToInt()
                    }
                }
            }
        }

        /** @return the monitor, or `null` if the response never stated its status. */
        fun build(): KumaMonitor? {
            if (statuses.isEmpty()) return null
            // Not empty by the line above, so no agreed value means more than one distinct value.
            val agreedStatus = statuses.singleOrNull()
            return KumaMonitor(
                monitorId = monitorId,
                name = names.smallest(),
                type = types.smallest(),
                // A monitor whose rows disagree is unknown, never one of the two guesses.
                status = agreedStatus ?: KumaMonitorStatus.UNKNOWN,
                statusContradicted = agreedStatus == null,
                responseTimeMs = responseTimes.agreedValue(),
                uptimeRatioByWindow = uptimeRatiosByWindow
                    .mapNotNull { (window, values) -> values.singleOrNull()?.let { window to it } }
                    .toMap(),
                certificateValid = certificateValidities.agreedValue(),
                certificateDaysRemaining = certificateDays.agreedValue(),
            )
        }

        /**
         * The value every row agreed on, or `null` when they did not — which is also what `null`
         * already means for each of these fields: no value to show.
         */
        private fun <T> Set<T?>.agreedValue(): T? = singleOrNull()

        /**
         * The smallest candidate by code point, or `null` when there was none.
         *
         * The comparison is explicitly the natural order of the string and not a locale's
         * collation, which would make the displayed name depend on the device's language setting.
         */
        private fun Set<String>.smallest(): String? = minWithOrNull(naturalOrder())
    }
}
