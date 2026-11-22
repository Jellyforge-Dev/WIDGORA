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

/** One monitor as the selection screen offers it: the identifier to store, and its name. */
data class MonitorChoice(val monitorId: String, val label: String)

/**
 * What the screen knows about the monitors of the chosen instance.
 *
 * Four states rather than a list and a flag. Reading the list means asking the instance, which
 * takes time and can fail, and each outcome needs a different line on the screen — "still asking",
 * "could not ask", "asked, and there are none" and "here they are" are four different things, and a
 * list that is empty in three of them tells the user nothing.
 */
sealed interface MonitorCatalogue {

    /** Not asked for yet. The state of a screen whose kind needs no monitor list. */
    data object Idle : MonitorCatalogue

    /** Being read. The instance is being asked, which is a network call. */
    data object Loading : MonitorCatalogue

    /** The instance could not be asked, or answered with something unusable. */
    data object Unavailable : MonitorCatalogue

    /** The monitors the instance reported, which may legitimately be none of them. */
    data class Monitors(val choices: List<MonitorChoice>) : MonitorCatalogue
}

/** Where the configuration screen gets the monitors of an instance from. */
interface MonitorCatalogueSource {

    /**
     * The monitors of one instance, or `null` where they could not be obtained.
     *
     * It suspends, and the implementation retrieves: this is the one screen that has to ask a
     * server a question the application has not necessarily asked yet, because a widget may be
     * placed on a device that has not opened the dashboard since it was started.
     */
    suspend fun monitorsOf(instanceId: InstanceId): List<MonitorChoice>?
}

/** What the configuration screen shows. */
data class WidgetConfigState(
    /** What sort of widget is being configured, read from the provider that started the screen. */
    val kind: WidgetKind,
    val instances: List<InstanceChoice> = emptyList(),
    /** The chosen instance, or `null` while nothing has been read or nothing exists. */
    val selectedInstanceId: InstanceId? = null,
    /** Whether this widget may name monitors. Off unless the user says otherwise. */
    val showNames: Boolean = false,
    /** The monitors on offer, for the one kind that picks them. */
    val monitors: MonitorCatalogue = MonitorCatalogue.Idle,
    /** The picked monitors, in the order they were picked. */
    val selectedMonitorIds: List<String> = emptyList(),
    /**
     * Whether the last pick was refused because the stored limit is reached.
     *
     * A refusal has to say something. A press that silently does nothing reads as a broken remote,
     * and on a television that is the one reading a user cannot check.
     */
    val pickRefused: Boolean = false,
    val loaded: Boolean = false,
    /** A stored configuration exists and cannot be read, so no instance can be offered. */
    val configurationUnreadable: Boolean = false,
    val saveFailed: Boolean = false,
) {

    /**
     * Nothing can be saved without an instance to point at — and, for a selection widget, without
     * a selection.
     *
     * The second half is not a formality. A selection widget with nothing picked has nothing to
     * show and nothing to name, and the picking is also what carries the consent for the names it
     * draws. Saving an empty one would store a widget that can only ever say "nothing picked".
     */
    val canSave: Boolean
        get() = selectedInstanceId != null &&
            (kind != WidgetKind.SELECTION || selectedMonitorIds.isNotEmpty())
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
 *
 * ### Where consent is recorded, per kind
 *
 * - **Status.** A switch, off unless the user turns it on.
 * - **Certificates.** No switch: a list of certificates is a list of the hosts they belong to, so
 *   a widget of that kind which may not name them has nothing to show. The screen says so plainly
 *   and pressing save is the agreement, which is stored as the same switch being on.
 * - **Selection.** The selection itself. Nothing is stored until save is pressed, and an empty
 *   selection cannot be saved, so a stored entry of this kind always carries monitors the user
 *   picked one at a time on a screen that said what picking means.
 */
class WidgetConfigStateHolder(
    private val appWidgetId: Int,
    kind: WidgetKind,
    private val instances: Flow<StoredInstances>,
    private val settings: WidgetSettingsStore,
    private val monitors: MonitorCatalogueSource,
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
     *
     * The monitor list is **not** read here. It depends on which instance is chosen, and the chosen
     * instance can change while the screen is open; the screen therefore asks for it whenever that
     * answer changes, and this function would otherwise ask for the first one twice.
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
            selectedMonitorIds = previous?.monitorIds.orEmpty(),
            loaded = true,
            configurationUnreadable = stored is StoredInstances.Unreadable,
            saveFailed = false,
        )
    }

    /**
     * Reads the monitors of one instance, for the kind that picks them.
     *
     * ### Why a picked monitor the instance no longer lists is dropped
     *
     * Only once the list has actually been read. A reconfiguration opens with the monitors stored
     * earlier, and an identifier missing from the list is one the source no longer reports: the
     * screen cannot offer it to be unpicked, so keeping it would be keeping an entry the user can
     * neither see nor remove — and it would count towards the limit for as long as the widget
     * exists. A failed read drops nothing, because a failed read is not evidence about anything.
     */
    suspend fun loadMonitors(instanceId: InstanceId) {
        mutableState.value = mutableState.value.copy(
            monitors = MonitorCatalogue.Loading,
            pickRefused = false,
        )

        val choices = monitors.monitorsOf(instanceId)
        if (choices == null) {
            mutableState.value = mutableState.value.copy(monitors = MonitorCatalogue.Unavailable)
            return
        }

        // An identifier this application could not store and read back unchanged is not offered at
        // all. The rule belongs to storage and is asked of it rather than restated here.
        val storable = choices.filter { choice ->
            WidgetSettings.isStorableMonitorId(choice.monitorId)
        }
        val offered = storable.map { choice -> choice.monitorId }.toSet()

        mutableState.value = mutableState.value.copy(
            monitors = MonitorCatalogue.Monitors(storable),
            selectedMonitorIds = mutableState.value.selectedMonitorIds
                .filter { monitorId -> monitorId in offered },
        )
    }

    fun selectInstance(instanceId: InstanceId) {
        val current = mutableState.value
        if (current.instances.none { it.instanceId == instanceId }) return
        if (current.selectedInstanceId == instanceId) return

        mutableState.value = current.copy(
            selectedInstanceId = instanceId,
            saveFailed = false,
            // Another instance has other monitors, and a selection made against one server means
            // nothing on another. Both the list and what was picked from it go.
            monitors = if (current.kind == WidgetKind.SELECTION) {
                MonitorCatalogue.Loading
            } else {
                current.monitors
            },
            selectedMonitorIds = if (current.kind == WidgetKind.SELECTION) {
                emptyList()
            } else {
                current.selectedMonitorIds
            },
            pickRefused = false,
        )
    }

    fun setShowNames(showNames: Boolean) {
        mutableState.value = mutableState.value.copy(showNames = showNames, saveFailed = false)
    }

    /**
     * Picks a monitor, or unpicks one that was already picked.
     *
     * The order is the order they were picked in, and it is kept: it is the user's own order, and
     * the widget shows the first few of it. Unpicking removes without reordering the rest.
     *
     * A pick beyond the stored limit is refused and says so. Silently dropping the press would
     * read as a broken remote, and silently dropping the oldest pick would be the application
     * deciding which of the user's monitors matters least.
     */
    fun toggleMonitor(monitorId: String) {
        val current = mutableState.value
        val offered = (current.monitors as? MonitorCatalogue.Monitors) ?: return
        if (offered.choices.none { choice -> choice.monitorId == monitorId }) return

        if (monitorId in current.selectedMonitorIds) {
            mutableState.value = current.copy(
                selectedMonitorIds = current.selectedMonitorIds - monitorId,
                pickRefused = false,
                saveFailed = false,
            )
            return
        }

        if (current.selectedMonitorIds.size >= WidgetSettings.MAX_SELECTED_MONITORS) {
            mutableState.value = current.copy(pickRefused = true)
            return
        }

        mutableState.value = current.copy(
            selectedMonitorIds = current.selectedMonitorIds + monitorId,
            pickRefused = false,
            saveFailed = false,
        )
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
        if (!current.canSave) return false
        val instanceId = current.selectedInstanceId ?: return false

        val outcome = settings.save(
            appWidgetId,
            WidgetSettings(
                kind = current.kind,
                instanceId = instanceId,
                showNames = when (current.kind) {
                    WidgetKind.STATUS -> current.showNames
                    // Save is the consent for this kind, and it is recorded as the same yes the
                    // switch records, so that one rule reads it back — see NameConsent.
                    WidgetKind.CERTIFICATES -> true
                    // Not a yes, and deliberately not stored as one: nobody turned a switch on
                    // here. The selection below is what carries the consent for this kind.
                    WidgetKind.SELECTION -> false
                },
                monitorIds = when (current.kind) {
                    WidgetKind.SELECTION -> current.selectedMonitorIds
                    // The settings type refuses identifiers on any other kind.
                    WidgetKind.STATUS, WidgetKind.CERTIFICATES -> emptyList()
                },
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
