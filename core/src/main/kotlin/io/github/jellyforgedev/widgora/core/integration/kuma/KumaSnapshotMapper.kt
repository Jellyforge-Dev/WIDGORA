package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetId
import io.github.jellyforgedev.widgora.model.WidgetSnapshot

/**
 * Builds the one summary widget of an instance from the monitors a response described.
 *
 * One widget per monitor is a later step. An installation with dozens of monitors would fill a
 * television row with single entries, and monitor names are exactly what the bridge keeps out
 * of its default output because they often contain internal host names.
 */
object KumaSnapshotMapper {

    /**
     * Symbolic names, resolved by whoever renders the widget. The vocabulary of tokens belongs
     * to the design system, which does not exist yet, so one neutral pair is used instead of
     * inventing a vocabulary here.
     */
    const val ICON_TOKEN = "monitor"
    const val ACCENT_TOKEN = "accent-default"

    private const val WIDGET_ID_PREFIX = "kuma-"
    private const val WIDGET_ID_SUMMARY_SUFFIX = "-summary"

    /**
     * Derived from the instance identifier alone, never from a monitor name or an address: it is
     * stable across restarts and carries nothing that would reveal what is being monitored.
     * [InstanceId] is limited to 32 characters, which keeps the result inside the 64 a
     * [WidgetId] permits.
     */
    fun summaryWidgetId(instanceId: InstanceId): WidgetId =
        WidgetId(WIDGET_ID_PREFIX + instanceId.value + WIDGET_ID_SUMMARY_SUFFIX)

    /**
     * @param title the instance's display name, chosen by the user
     * @param retrievedAtEpochMs when the response was fetched; the endpoint carries no timestamp
     *   of its own, so the age of the data is the age of the retrieval
     */
    fun summarySnapshot(
        instanceId: InstanceId,
        title: String,
        monitors: List<KumaMonitor>,
        retrievedAtEpochMs: Long,
        templates: KumaSummaryTemplates,
    ): WidgetSnapshot = WidgetSnapshot.truncating(
        widgetId = summaryWidgetId(instanceId),
        title = title,
        primaryValue = summaryText(monitors, templates),
        updatedAtEpochMs = retrievedAtEpochMs,
        iconToken = ICON_TOKEN,
        accentToken = ACCENT_TOKEN,
    )

    /**
     * An installation without monitors yields an empty value on purpose: that is what makes the
     * widget report that there is nothing to show, rather than zero of zero being up.
     *
     * The counts and their order are decided here; every word comes from [templates]. Up and
     * down are always named, even at zero, so that a glance tells the whole story. The other
     * three appear only when they occur, because naming three zeroes buries the two numbers
     * that matter.
     */
    private fun summaryText(
        monitors: List<KumaMonitor>,
        templates: KumaSummaryTemplates,
    ): String {
        if (monitors.isEmpty()) return ""

        val parts = mutableListOf(
            templates.up(monitors.count { it.status.countsAsUp }),
            templates.down(monitors.count { it.status.countsAsDown }),
        )
        appendIfAny(parts, monitors.count { it.status == KumaMonitorStatus.PENDING }, templates.pending)
        appendIfAny(
            parts,
            monitors.count { it.status == KumaMonitorStatus.MAINTENANCE },
            templates.maintenance,
        )
        // An unrecognised state is named rather than folded into one of the known counts.
        appendIfAny(parts, monitors.count { it.status == KumaMonitorStatus.UNKNOWN }, templates.unknown)
        return parts.joinToString(templates.separator)
    }

    private fun appendIfAny(parts: MutableList<String>, count: Int, phrase: (Int) -> String) {
        if (count > 0) parts.add(phrase(count))
    }
}
