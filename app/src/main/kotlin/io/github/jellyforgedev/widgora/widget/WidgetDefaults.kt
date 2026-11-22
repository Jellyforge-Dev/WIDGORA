package io.github.jellyforgedev.widgora.widget

import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.core.data.WidgetSettings
import io.github.jellyforgedev.widgora.model.InstanceId

/**
 * What applies to a placed widget that has no stored entry.
 *
 * ### Why such a widget exists at all
 *
 * Every widget placed from now on runs the configuration step and is stored. The ones placed
 * **before** this change were not: they were put on a screen at a time when there was nothing to
 * configure, and they are still there. The platform does not run a configuration step
 * retroactively, so those widgets read their settings, find none, and have to say something.
 *
 * Saying "not set up" would be false and would also be a regression: the widget worked yesterday,
 * the user changed nothing, and it would now ask them to repair something that is not broken.
 *
 * ### Why this is a defined default and not a guess
 *
 * It restates exactly what such a widget already did before there were settings — the first
 * stored instance, and no monitor name — so it is the behaviour being preserved rather than
 * invented. Writing it down as a rule in one function, with a test, is the difference between a
 * promise and a side effect of the order a list happens to be in.
 *
 * The missing entry is **not** written to storage. Nothing asked the user the question, so
 * recording an answer in their name would turn a fallback into a consent they never gave; it also
 * keeps the one meaning of a stored entry intact, which is that the configuration step wrote it.
 */
object WidgetDefaults {

    /**
     * The settings that apply when nothing is stored for a widget of this [kind].
     *
     * @param firstInstanceId the first stored instance, or `null` when none is set up
     * @return the default, or `null` for a kind that cannot be served without an answer
     */
    fun withoutAnEntry(kind: WidgetKind, firstInstanceId: InstanceId?): WidgetSettings? =
        when (kind) {
            // The one kind that existed before there were settings, so the one kind with a
            // widget in the field to stay compatible with.
            WidgetKind.STATUS -> firstInstanceId?.let { instanceId ->
                WidgetSettings(
                    kind = WidgetKind.STATUS,
                    instanceId = instanceId,
                    // Names stay off until the user says otherwise. A default that turned them on
                    // would be consent nobody gave, which is the one thing this project will not
                    // infer.
                    showNames = false,
                )
            }

            // No default, deliberately. Neither kind has ever been placed, so there is no earlier
            // behaviour to preserve, and both would need an answer that cannot be derived: a
            // selection is a list only the user has, and a certificate widget pointed at
            // whichever instance came first would be the silent re-pointing the project rejects.
            // Such a widget says it is not set up, and the configuration step is how it is.
            WidgetKind.CERTIFICATES, WidgetKind.SELECTION -> null
        }
}
