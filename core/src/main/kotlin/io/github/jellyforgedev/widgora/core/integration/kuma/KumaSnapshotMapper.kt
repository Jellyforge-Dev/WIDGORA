package io.github.jellyforgedev.widgora.core.integration.kuma

import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetId
import io.github.jellyforgedev.widgora.model.WidgetSnapshot

/**
 * Builds the one summary widget of an instance from what a response described.
 *
 * One widget per monitor is a later step. An installation with dozens of monitors would fill a
 * television row with single entries, and monitor names are exactly what the bridge keeps out of
 * its output because they often contain internal host names.
 */
object KumaSnapshotMapper {

    /** Symbolic names, resolved by whoever renders the widget. */
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
     * @param overview the retrieved items. Only its counts are read: the names it also holds must
     *   never enter a snapshot, because a snapshot is what the bridge hands to a foreign
     *   application.
     * @param retrievedAtEpochMs when the response was fetched; the endpoint carries no timestamp
     *   of its own, so the age of the data is the age of the retrieval
     *
     * The value is assembled from marks and digits and contains no word in any language. A
     * snapshot may travel through the bridge to a host launcher that neither shares WIDGORA's
     * resources nor transports its colours, so a localised phrase would be the wrong thing to
     * carry, and a distinction made by colour alone would arrive as no distinction at all. The
     * application's own tile does not read this text: it draws the same marks from the structured
     * counts and adds the words from its resources for anyone using a screen reader.
     */
    fun summarySnapshot(
        instanceId: InstanceId,
        title: String,
        overview: MonitorOverview,
        retrievedAtEpochMs: Long,
    ): WidgetSnapshot = WidgetSnapshot.truncating(
        widgetId = summaryWidgetId(instanceId),
        title = title,
        primaryValue = overview.counts.neutralSummary(),
        updatedAtEpochMs = retrievedAtEpochMs,
        iconToken = ICON_TOKEN,
        accentToken = ACCENT_TOKEN,
    )
}
