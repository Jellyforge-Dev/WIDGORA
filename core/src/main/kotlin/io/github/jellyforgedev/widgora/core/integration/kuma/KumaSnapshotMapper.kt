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
    ): WidgetSnapshot = WidgetSnapshot.truncating(
        widgetId = summaryWidgetId(instanceId),
        title = title,
        primaryValue = summaryText(monitors),
        updatedAtEpochMs = retrievedAtEpochMs,
        iconToken = ICON_TOKEN,
        accentToken = ACCENT_TOKEN,
    )

    /**
     * An installation without monitors yields an empty value on purpose: that is what makes the
     * widget report that there is nothing to show, rather than zero of zero being up.
     *
     * The wording is English and assembled here, which is a known gap: display text should come
     * from a presentation layer that can localise it, and there is none yet.
     */
    private fun summaryText(monitors: List<KumaMonitor>): String {
        if (monitors.isEmpty()) return ""

        val parts = mutableListOf(
            monitors.count { it.status.countsAsUp }.toString() + " up",
            monitors.count { it.status.countsAsDown }.toString() + " down",
        )
        appendIfAny(parts, monitors.count { it.status == KumaMonitorStatus.PENDING }, "pending")
        appendIfAny(
            parts,
            monitors.count { it.status == KumaMonitorStatus.MAINTENANCE },
            "in maintenance",
        )
        // An unrecognised state is named rather than folded into one of the known counts.
        appendIfAny(parts, monitors.count { it.status == KumaMonitorStatus.UNKNOWN }, "unknown")
        return parts.joinToString(", ")
    }

    private fun appendIfAny(parts: MutableList<String>, count: Int, label: String) {
        if (count > 0) parts.add(count.toString() + " " + label)
    }
}
