package io.github.jellyforgedev.widgora.widget

import io.github.jellyforgedev.widgora.core.data.StoredInstance
import io.github.jellyforgedev.widgora.core.data.StoredInstances
import io.github.jellyforgedev.widgora.core.data.WidgetView
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.model.WidgetDisplayState

/**
 * Decides what a widget shows, given what storage holds and what the application holds.
 *
 * It is separated from [WidgetUpdater] for one reason: this is the part worth testing, and the
 * part around it is Android — a widget manager, a context, a package name. Here there is nothing
 * but values in and a value out, so every case can be stated as a test instead of being reasoned
 * about over a device.
 *
 * ### Memory outranks storage wherever memory has anything
 *
 * Both entry points prefer what the application already knows. The reason is that the two sources
 * answer different questions. Storage says *which* instance exists; the snapshot repository says
 * *how it is doing*. A widget update can arrive while the application is running and has just
 * retrieved — the user looking at the dashboard, a resize inside the host — and saying "no data
 * yet" then would be false while the truth was a field away.
 */
object WidgetModelSelection {

    /**
     * For an update that arrives at a process which may have only just been started for it.
     *
     * Storage is read because an empty list of views is ambiguous here: it means "nothing
     * retrieved yet", which is not the same statement as "nothing set up", and a widget must not
     * confuse the two. Memory is then preferred wherever it holds something for that instance.
     */
    fun fromStorage(
        stored: StoredInstances,
        views: List<WidgetView>,
        nowEpochMs: Long,
    ): WidgetRenderModel = when (stored) {
        is StoredInstances.Unreadable -> WidgetRenderModel.ConfigurationUnreadable

        is StoredInstances.Readable -> {
            val instance = stored.instances.firstOrNull()
            if (instance == null) {
                WidgetRenderModel.NotSetUp
            } else {
                // Until a widget can be pointed at an instance of its own, it shows the first
                // one. With one instance configured that is the only one; with several it is a
                // stand-in, and choosing is the next step's job.
                views.firstOrNull { view -> view.instanceId == instance.instanceId }
                    ?.let { view -> modelOf(view, nowEpochMs) }
                    ?: nothingRetrievedYet(titleOf(instance), nowEpochMs)
            }
        }
    }

    /**
     * For the running application, where the repository has already read storage.
     *
     * No file is read: by this point an empty list of views genuinely means nothing is
     * configured, and the unreadable case is a flag the repository publishes beside the views.
     */
    fun fromMemory(
        configurationUnreadable: Boolean,
        views: List<WidgetView>,
        nowEpochMs: Long,
    ): WidgetRenderModel = when {
        configurationUnreadable -> WidgetRenderModel.ConfigurationUnreadable
        else -> views.firstOrNull()
            ?.let { view -> modelOf(view, nowEpochMs) }
            ?: WidgetRenderModel.NotSetUp
    }

    /**
     * Turns one view into a render model.
     *
     * It reads three of the view's fields and leaves the fourth alone. That fourth one is the
     * item list, which holds the monitor names, and it has no business in anything a launcher
     * receives.
     */
    private fun modelOf(view: WidgetView, nowEpochMs: Long): WidgetRenderModel =
        WidgetRenderModels.forInstance(
            title = view.title,
            state = view.state,
            snapshot = view.snapshot,
            nowEpochMs = nowEpochMs,
        )

    /**
     * An instance that is known to exist and about which nothing has been retrieved.
     *
     * "Loading" is the honest word for it. Working out the instance's situation here instead —
     * whether it is switched off, whether its credential is usable — would put the repository's
     * own reasoning in a second place to keep in step, and would claim more than this code knows.
     */
    private fun nothingRetrievedYet(title: String, nowEpochMs: Long): WidgetRenderModel =
        WidgetRenderModels.forInstance(
            title = title,
            state = WidgetViewState.Display(WidgetDisplayState.Loading),
            snapshot = null,
            nowEpochMs = nowEpochMs,
        )

    private fun titleOf(instance: StoredInstance): String = when (instance) {
        is StoredInstance.Valid -> instance.config.displayName
        // An invalid entry may have lost its name as well; the identifier is then all there is to
        // address it by.
        is StoredInstance.Invalid -> instance.displayName?.takeIf { it.isNotBlank() }
            ?: instance.instanceId.value
    }
}
