package io.github.jellyforgedev.widgora.core.integration.kuma

/**
 * One monitor, as far as the metrics endpoint describes it.
 *
 * There is deliberately no field for the monitored address, host name or port. The endpoint
 * reports all three, and all three are internal infrastructure detail that nothing downstream
 * needs in order to display a status. Leaving them out of the type is more reliable than
 * remembering not to pass them on.
 *
 * Identity is [monitorId] alone. Monitor names are not unique: the same name can belong to two
 * different monitors.
 */
data class KumaMonitor(
    val monitorId: String,
    val name: String?,
    val type: String?,
    val status: KumaMonitorStatus,
    val responseTimeMs: Long?,
    val uptimeRatioByWindow: Map<String, Double>,
    val certificateValid: Boolean?,
    val certificateDaysRemaining: Int?,
    /**
     * The response carried at least one certificate row for this monitor.
     *
     * Without it the two fields above cannot answer the question anybody actually asks. Both are
     * `null` for a monitor the source holds no certificate information about — a ping check — and
     * `null` again for one it does hold information about but described inconsistently. Those are
     * different things: the first has no certificate to talk about, the second has one whose
     * validity is unknown. This flag is what keeps "not applicable" apart from "not known", and it
     * follows the presence of a row rather than the usefulness of its value, because a row the
     * source wrote is the source saying this monitor has a certificate at all.
     */
    val certificateReported: Boolean = false,
    /**
     * The response stated this monitor's state more than once and the statements disagreed, which
     * is why [status] is [KumaMonitorStatus.UNKNOWN].
     *
     * It separates two situations that look identical on a screen and are not the same thing. One
     * is a fault in what the service is sending, which a restart of that service clears; the other
     * is a state this version has not learned yet, about which there is nothing useful to say. Only
     * the first one has advice to offer, and without this flag it could not be offered.
     */
    val statusContradicted: Boolean = false,
)
