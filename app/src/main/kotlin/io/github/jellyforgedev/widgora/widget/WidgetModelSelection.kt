package io.github.jellyforgedev.widgora.widget

import io.github.jellyforgedev.widgora.core.data.StoredInstance
import io.github.jellyforgedev.widgora.core.data.StoredInstances
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.core.data.WidgetSettings
import io.github.jellyforgedev.widgora.core.data.WidgetView
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState

/**
 * Decides what one widget shows, given its own settings, what storage holds and what the
 * application holds.
 *
 * It is separated from [WidgetUpdater] for one reason: this is the part worth testing, and the
 * part around it is Android — a widget manager, a context, a package name. Here there is nothing
 * but values in and a value out, so every case can be stated as a test instead of being reasoned
 * about over a device.
 *
 * ### One decision per widget, not one per push
 *
 * Both entry points take the settings of **one** widget. Before there were settings, every widget
 * of a provider was handed the same model; now two widgets of the same provider can point at
 * different instances and differ in whether they may name monitors, so the model is built per
 * placement and [WidgetUpdater] loops.
 *
 * ### This is where consent is read, and the only place
 *
 * The return value already says which of the two renderers applies: a [WidgetRender.Named] can
 * only be built from a consent token, and a token can only be made from stored settings that say
 * yes. Nothing downstream re-decides it — see [NameConsent].
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
     *
     * @param kind what sort of widget this is, read from the provider the update is for
     * @param settings what the configuration step stored for it, or `null` when it stored nothing
     */
    fun fromStorage(
        kind: WidgetKind,
        settings: WidgetSettings?,
        stored: StoredInstances,
        views: List<WidgetView>,
        nowEpochMs: Long,
    ): WidgetRender = when (stored) {
        is StoredInstances.Unreadable ->
            WidgetRender.Nameless(WidgetRenderModel.ConfigurationUnreadable)

        is StoredInstances.Readable -> {
            val available = stored.instances.map { instance -> instance.instanceId }
            when (val chosen = chosenInstance(kind, settings, available)) {
                is Chosen.Nothing -> WidgetRender.Nameless(chosen.model)

                is Chosen.Instance -> {
                    val instance = stored.instances.first { it.instanceId == chosen.instanceId }
                    render(
                        settings = chosen.settings,
                        view = views.firstOrNull { it.instanceId == chosen.instanceId },
                        fallbackTitle = titleOf(instance),
                        nowEpochMs = nowEpochMs,
                    )
                }
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
        kind: WidgetKind,
        settings: WidgetSettings?,
        configurationUnreadable: Boolean,
        views: List<WidgetView>,
        nowEpochMs: Long,
    ): WidgetRender = when {
        configurationUnreadable ->
            WidgetRender.Nameless(WidgetRenderModel.ConfigurationUnreadable)

        else -> {
            val available = views.map { view -> view.instanceId }
            when (val chosen = chosenInstance(kind, settings, available)) {
                is Chosen.Nothing -> WidgetRender.Nameless(chosen.model)

                is Chosen.Instance -> {
                    val view = views.first { view -> view.instanceId == chosen.instanceId }
                    render(
                        settings = chosen.settings,
                        view = view,
                        fallbackTitle = view.title,
                        nowEpochMs = nowEpochMs,
                    )
                }
            }
        }
    }

    /** Which instance this widget is for, or the statement that it has none. */
    private sealed interface Chosen {

        data class Instance(val instanceId: InstanceId, val settings: WidgetSettings) : Chosen

        data class Nothing(val model: WidgetRenderModel) : Chosen
    }

    /**
     * Applies the settings, or the defined default where there are none.
     *
     * The three outcomes are kept apart deliberately:
     *
     * - **No instance at all.** The widget says nothing is set up, which is the truth about the
     *   application rather than about this widget, and names the one thing to do about it.
     * - **The configured instance is gone.** Other instances may exist and this widget is not for
     *   them. Re-pointing it at the first one would make a widget report on a server the user
     *   never chose for it, which is the one outcome ARCHITECTURE.md rules out by name.
     * - **A kind with no default and no entry.** Only reachable for the two kinds that do not
     *   exist yet; see [WidgetDefaults].
     */
    private fun chosenInstance(
        kind: WidgetKind,
        settings: WidgetSettings?,
        available: List<InstanceId>,
    ): Chosen {
        val effective = settings
            ?: WidgetDefaults.withoutAnEntry(kind, available.firstOrNull())
            ?: return Chosen.Nothing(WidgetRenderModel.NotSetUp)

        if (effective.instanceId !in available) {
            // Nothing set up at all is reported as such even for a widget that names an instance:
            // the action is the same — set an instance up — and "not set up" is the accurate
            // statement about an application with no instances in it.
            val model = if (available.isEmpty()) {
                WidgetRenderModel.NotSetUp
            } else {
                WidgetRenderModel.InstanceGone
            }
            return Chosen.Nothing(model)
        }
        return Chosen.Instance(effective.instanceId, effective)
    }

    /**
     * Turns one view into the render this widget's settings call for.
     *
     * The nameless model is built exactly as it always was, from three of the view's fields. The
     * fourth — the item list, which holds the monitor names — is read **only** when a consent
     * token could be produced, and such a token can only come from a stored yes.
     */
    private fun render(
        settings: WidgetSettings,
        view: WidgetView?,
        fallbackTitle: String,
        nowEpochMs: Long,
    ): WidgetRender {
        val instance = if (view == null) {
            nothingRetrievedYet(fallbackTitle, nowEpochMs)
        } else {
            WidgetRenderModels.forInstance(
                title = view.title,
                state = view.state,
                snapshot = view.snapshot,
                nowEpochMs = nowEpochMs,
            )
        }

        val consent = NameConsent.from(settings) ?: return WidgetRender.Nameless(instance)
        return WidgetRender.Named(
            NamedWidgetRenderModel.of(
                consent = consent,
                instance = instance,
                overview = view?.overview,
            ),
        )
    }

    /**
     * An instance that is known to exist and about which nothing has been retrieved.
     *
     * "Loading" is the honest word for it. Working out the instance's situation here instead —
     * whether it is switched off, whether its credential is usable — would put the repository's
     * own reasoning in a second place to keep in step, and would claim more than this code knows.
     */
    private fun nothingRetrievedYet(
        title: String,
        nowEpochMs: Long,
    ): WidgetRenderModel.Instance = WidgetRenderModels.forInstance(
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
