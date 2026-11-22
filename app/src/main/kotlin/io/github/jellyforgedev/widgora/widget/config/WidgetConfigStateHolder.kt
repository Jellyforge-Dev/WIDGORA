package io.github.jellyforgedev.widgora.widget.config

import io.github.jellyforgedev.widgora.core.data.StorageWriteResult
import io.github.jellyforgedev.widgora.core.data.StoredInstance
import io.github.jellyforgedev.widgora.core.data.StoredInstances
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.core.data.WidgetSettings
import io.github.jellyforgedev.widgora.model.InstanceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * One instance as the configuration screen offers it.
 *
 * It carries a name and an identifier and nothing else. An address is not offered, not shown and
 * not storable here: a widget points at an instance the application has already set up, and
 * everything secret or topological stays where that instance keeps it.
 */
data class InstanceChoice(val instanceId: InstanceId, val label: String)

/** What the configuration screen shows. */
data class WidgetConfigState(
    /** What sort of widget is being configured, read from the provider that started the screen. */
    val kind: WidgetKind,
    val instances: List<InstanceChoice> = emptyList(),
    /** The chosen instance, or `null` while nothing has been read or nothing exists. */
    val selectedInstanceId: InstanceId? = null,
    /** Whether this widget may name monitors. Off unless the user says otherwise. */
    val showNames: Boolean = false,
    val loaded: Boolean = false,
    /** A stored configuration exists and cannot be read, so no instance can be offered. */
    val configurationUnreadable: Boolean = false,
    val saveFailed: Boolean = false,
) {

    /** Nothing can be saved without an instance to point at. */
    val canSave: Boolean get() = selectedInstanceId != null
}

/** What the configuration screen needs in order to store an answer. */
interface WidgetSettingsStore {

    suspend fun settingsFor(appWidgetId: Int): WidgetSettings?

    suspend fun save(appWidgetId: Int, settings: WidgetSettings): StorageWriteResult
}

/**
 * The configuration screen's state, apart from Android.
 *
 * The same arrangement as the settings screen's holder and for the same reason: the repositories
 * behind it only exist with real preference files, so the decisions worth testing are put where a
 * unit test can reach them and the screen renders what it is told.
 *
 * ### Why the whole entry is written at once
 *
 * The screen asks every question before anything is stored, and [save] writes one complete entry.
 * A partial write would leave an answer from an earlier configuration standing beside the new
 * ones — which for the consent question means a yes that was given about something else.
 *
 * ### Why an unknown kind is not a case here
 *
 * The kind is settled before this holder exists: the activity reads it from the provider that
 * started it and refuses to go on without one. So this type takes a kind rather than a nullable
 * one, and the screen never has to render "unknown type".
 */
class WidgetConfigStateHolder(
    private val appWidgetId: Int,
    kind: WidgetKind,
    private val instances: Flow<StoredInstances>,
    private val settings: WidgetSettingsStore,
) {

    private val mutableState = MutableStateFlow(WidgetConfigState(kind = kind))

    val state: StateFlow<WidgetConfigState> get() = mutableState

    /**
     * Reads the instances and whatever was stored for this widget before.
     *
     * Called once, when the screen opens. It is also the path a reconfiguration takes — a host
     * starting this screen again for a widget that already has an entry — which is why the stored
     * answers are read rather than assumed absent. A widget being reconfigured shows what it is
     * set to now, not a fresh set of defaults.
     */
    suspend fun load() {
        val stored = instances.first()
        val previous = settings.settingsFor(appWidgetId)

        val choices = when (stored) {
            is StoredInstances.Unreadable -> emptyList()
            is StoredInstances.Readable -> stored.instances.map { instance -> choiceOf(instance) }
        }

        mutableState.value = mutableState.value.copy(
            instances = choices,
            // The stored instance wins where it still exists, so a reconfiguration opens on the
            // answer already given. Otherwise the first one is pre-selected, which is the whole
            // of "pre-selected when only one exists" — and with several it is a starting point the
            // user moves away from rather than an answer given on their behalf: nothing is stored
            // until they press save.
            selectedInstanceId = previous?.instanceId
                ?.takeIf { id -> choices.any { choice -> choice.instanceId == id } }
                ?: choices.firstOrNull()?.instanceId,
            showNames = previous?.showNames ?: false,
            loaded = true,
            configurationUnreadable = stored is StoredInstances.Unreadable,
            saveFailed = false,
        )
    }

    fun selectInstance(instanceId: InstanceId) {
        if (mutableState.value.instances.none { it.instanceId == instanceId }) return
        mutableState.value = mutableState.value.copy(
            selectedInstanceId = instanceId,
            saveFailed = false,
        )
    }

    fun setShowNames(showNames: Boolean) {
        mutableState.value = mutableState.value.copy(showNames = showNames, saveFailed = false)
    }

    /**
     * Stores the answers, and reports whether anything was written.
     *
     * The result is what the activity gates its own result on: `RESULT_OK` is sent only after a
     * write that reported success, so a host is never told a widget is configured when nothing
     * reached the file.
     */
    suspend fun save(): Boolean {
        val current = mutableState.value
        val instanceId = current.selectedInstanceId ?: return false

        val outcome = settings.save(
            appWidgetId,
            WidgetSettings(
                kind = current.kind,
                instanceId = instanceId,
                showNames = current.showNames,
                // The selection type is the one kind that stores identifiers, and it has no
                // provider yet. An empty list is not a placeholder for it: it is what every other
                // kind stores, and the settings type refuses a non-empty one for them.
                monitorIds = emptyList(),
            ),
        )

        if (outcome != StorageWriteResult.DONE) {
            mutableState.value = current.copy(saveFailed = true)
            return false
        }
        return true
    }

    /**
     * What an instance is called on this screen.
     *
     * An entry the current rules reject still appears, by name where it has one and by identifier
     * otherwise. Hiding it would leave the user unable to point a widget at an instance they can
     * see in the application's own settings; whether it can be retrieved from is a question for
     * the dashboard and not for this screen.
     */
    private fun choiceOf(instance: StoredInstance): InstanceChoice = InstanceChoice(
        instanceId = instance.instanceId,
        label = when (instance) {
            is StoredInstance.Valid -> instance.config.displayName
            is StoredInstance.Invalid -> instance.displayName?.takeIf { it.isNotBlank() }
                ?: instance.instanceId.value
        },
    )
}
