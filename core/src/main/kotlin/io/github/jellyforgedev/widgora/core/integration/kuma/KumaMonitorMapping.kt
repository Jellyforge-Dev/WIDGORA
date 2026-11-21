package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.core.R
import io.github.jellyforgedev.widgora.core.data.CertificateInfo
import io.github.jellyforgedev.widgora.core.data.CertificateValidity
import io.github.jellyforgedev.widgora.core.data.MonitorEntry
import io.github.jellyforgedev.widgora.core.data.MonitorNote
import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.core.data.MonitorState

/**
 * Translates this service's monitors into the application's own vocabulary.
 *
 * This is the only place that knows both sides. Everything downstream — the dashboard, the detail
 * screen, the counts, the ordering — works on [MonitorOverview] and never learns which service
 * the data came from. A second integration adds a second mapping here and changes nothing else.
 *
 * The dependency points this way round on purpose: the adapter knows the application's type, not
 * the other way about.
 */
object KumaMonitorMapping {

    fun overviewOf(monitors: List<KumaMonitor>): MonitorOverview =
        MonitorOverview(monitors.map { monitor -> entryOf(monitor) })

    private fun entryOf(monitor: KumaMonitor): MonitorEntry = MonitorEntry.truncating(
        monitorId = monitor.monitorId,
        // The endpoint reports a missing name as an absent label, and the reader turns that into
        // null. The identifier is then the only thing left to address the item by, and it is a
        // number the service assigned rather than anything about the monitored host.
        name = monitor.name?.takeIf { it.isNotBlank() } ?: monitor.monitorId,
        state = stateOf(monitor.status),
        responseTimeMs = monitor.responseTimeMs,
        note = noteOf(monitor),
        certificate = certificateOf(monitor),
    )

    /**
     * The certificate of one item, or nothing where the source said nothing.
     *
     * The absence is the point of the first line. This endpoint writes its two certificate gauges
     * only for a monitor it holds certificate information about, so no row means no certificate
     * rather than an unknown one. Fifteen of a captured installation's thirty-five monitors are in
     * that position `[V]`, and handing each of them a value with empty fields would make "does not
     * apply" indistinguishable from "nobody knows".
     *
     * The validity is translated rather than passed through. A no from this source does not mean
     * the certificate is bad — the gauge is written as zero for every case that is not an explicit
     * yes — so it becomes the statement that can actually be defended. `null`, which is what both
     * an unrecognised value and two disagreeing rows leave behind, becomes unknown.
     */
    private fun certificateOf(monitor: KumaMonitor): CertificateInfo? {
        if (!monitor.certificateReported) return null
        return CertificateInfo(
            validity = when (monitor.certificateValid) {
                true -> CertificateValidity.VALID_ACCORDING_TO_SOURCE
                false -> CertificateValidity.NOT_VALID_ACCORDING_TO_SOURCE
                null -> CertificateValidity.UNKNOWN
            },
            daysRemaining = monitor.certificateDaysRemaining,
        )
    }

    /**
     * The explanation for a state that the state alone cannot account for.
     *
     * Only one condition produces one today, and it is the condition a user would otherwise have
     * no way of understanding: the service answered twice about the same monitor and the two
     * answers disagreed, so the item reads as unknown while the service's own interface shows it
     * working. Without a word of explanation that looks like a defect in WIDGORA.
     *
     * The advice is this module's to give, and it is given as a resource of this module. The
     * condition is a general one that any source could produce; the remedy — restarting the
     * service — is true of this service and would be wrong to say about another.
     *
     * A status this version does not recognise deliberately gets no note. It is also unknown, but
     * there is nothing the user can do about it and no honest sentence to offer.
     */
    private fun noteOf(monitor: KumaMonitor): MonitorNote? = if (monitor.statusContradicted) {
        MonitorNote(
            kind = MonitorNote.Kind.CONTRADICTORY_SOURCE_DATA,
            sourceAdvice = R.string.kuma_note_contradicting_rows,
        )
    } else {
        null
    }

    /**
     * An exhaustive `when` rather than a lookup table: adding a wire state later must fail to
     * compile here instead of quietly becoming one of the five.
     */
    private fun stateOf(status: KumaMonitorStatus): MonitorState = when (status) {
        KumaMonitorStatus.UP -> MonitorState.ACTIVE
        KumaMonitorStatus.DOWN -> MonitorState.FAILED
        KumaMonitorStatus.PENDING -> MonitorState.PENDING
        KumaMonitorStatus.MAINTENANCE -> MonitorState.MAINTENANCE
        KumaMonitorStatus.UNKNOWN -> MonitorState.UNKNOWN
    }
}
