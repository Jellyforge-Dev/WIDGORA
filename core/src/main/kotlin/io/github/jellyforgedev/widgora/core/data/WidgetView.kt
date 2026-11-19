package io.github.jellyforgedev.widgora.core.data

import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import io.github.jellyforgedev.widgora.model.WidgetId
import io.github.jellyforgedev.widgora.model.WidgetSnapshot

/**
 * Why an instance is not being retrieved at all.
 *
 * A notice is not a display state: the six display states describe data that was or was not
 * obtained, while these describe a configuration the user has to act on. Mapping them onto an
 * error state would tell the user that something went wrong when nothing was even attempted.
 */
enum class InstanceNotice {

    /** The user switched the instance off. */
    DISABLED,

    /**
     * There is no usable credential: either none was stored, or the stored one can no longer be
     * decrypted. Both lead to the same action, which is to enter it again.
     */
    SECRET_UNUSABLE,

    /** The stored entry no longer satisfies the rules and has to be corrected or removed. */
    ENTRY_INVALID,
}

/** What a tile shows: either a display state with its data, or a notice. */
sealed interface WidgetViewState {

    data class Display(val displayState: WidgetDisplayState) : WidgetViewState

    data class Notice(val notice: InstanceNotice) : WidgetViewState
}

/**
 * One instance as the dashboard sees it.
 *
 * [snapshot] is the last one that was obtained successfully. It survives a failed retrieval on
 * purpose and is then shown together with its own timestamp, while [state] says that the data is
 * outdated or that the service is unreachable. A failure must never look like a current value.
 * [overview] survives a failure the same way and for the same reason.
 *
 * There is no field for a credential, and none of these types carries one.
 *
 * **Why the monitor names are here and not in the snapshot.** This type is read by WIDGORA's own
 * screens and by nothing else. A snapshot is read by a host launcher through the bridge, and a
 * monitor name routinely contains an internal host name. Keeping the names in a type the bridge
 * module cannot even reference — `:bridge` depends on `:model`, never on this module — makes that
 * boundary structural instead of something to remember.
 */
data class WidgetView(
    val instanceId: InstanceId,
    val widgetId: WidgetId,
    val title: String,
    val snapshot: WidgetSnapshot?,
    val state: WidgetViewState,
    /** The items behind the summary, or `null` while nothing has been retrieved yet. */
    val overview: MonitorOverview? = null,
)
