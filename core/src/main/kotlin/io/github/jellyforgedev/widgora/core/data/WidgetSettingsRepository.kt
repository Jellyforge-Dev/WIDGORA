package io.github.jellyforgedev.widgora.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jellyforgedev.widgora.model.InstanceId
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Which of the three widgets a placement is. */
enum class WidgetKind {

    /** The counts per state for one instance. */
    STATUS,

    /** Items whose certificate expires within the horizon, soonest first. */
    CERTIFICATES,

    /** Items the user picked, with their state. */
    SELECTION,
    ;

    companion object {

        /**
         * The kind stored under this name, or `null` for anything else.
         *
         * `null` rather than a default, because a stored kind this version does not recognise is
         * not a widget that happens to be of another type — it is an entry that cannot be
         * honoured, and the widget says it is not set up. Falling back to [STATUS] would show a
         * counts widget to somebody who placed a certificate one.
         */
        fun parseOrNull(stored: String?): WidgetKind? =
            entries.firstOrNull { kind -> kind.name == stored }
    }
}

/**
 * What one placed widget was configured to show.
 *
 * **There is no field for a credential, an address or a host name, and that is structural.** A
 * widget points at an instance the application has already set up, by identifier, and everything
 * secret or topological stays where that instance keeps it. A test asserts this type's field list,
 * so gaining such a field has to be a decision somebody makes in review rather than a line that
 * slips in.
 *
 * @param showNames whether monitor names may appear in this widget. False unless the user said
 *   otherwise, and per placement: consent given for one widget says nothing about another.
 * @param monitorIds the items chosen for a [WidgetKind.SELECTION] widget, in the order the user put
 *   them in, and empty for the other two kinds. The order is part of the value, which is why this
 *   is a list and why storage keeps it as a joined string rather than as a set.
 */
data class WidgetSettings(
    val kind: WidgetKind,
    val instanceId: InstanceId,
    val showNames: Boolean = false,
    val monitorIds: List<String> = emptyList(),
) {

    init {
        require(kind == WidgetKind.SELECTION || monitorIds.isEmpty()) {
            "only a selection widget carries monitor identifiers"
        }
        require(monitorIds.size <= MAX_SELECTED_MONITORS) {
            "a selection holds at most $MAX_SELECTED_MONITORS items"
        }
        require(monitorIds.all { id -> isStorableMonitorId(id) }) {
            "a monitor identifier is not of the expected form"
        }
    }

    companion object {

        /**
         * How many items one selection widget may name.
         *
         * A ceiling on storage rather than on display — a widget draws far fewer — and its job is
         * that a stored entry cannot grow without bound. An installation with a thousand monitors
         * must not be able to produce a preference value that has to be parsed at every update.
         */
        const val MAX_SELECTED_MONITORS = 20

        /**
         * The character the stored identifiers are joined with.
         *
         * It is excluded from [isStorableMonitorId], so the join is reversible by construction
         * rather than by escaping. An identifier that would break the round trip is refused when
         * it is offered, not repaired when it is read back.
         */
        const val MONITOR_ID_SEPARATOR = ","

        /** Long enough for an identifier of any shape a service plausibly assigns. */
        const val MAX_MONITOR_ID_LENGTH = 64

        /**
         * Whether an identifier can be stored and read back unchanged.
         *
         * A whitelist, and deliberately a statement about **storage** rather than about what a
         * service may call its monitors. The first integration numbers them and a second may use
         * dotted or dashed names; what none of them may do is put the separator, whitespace or a
         * control character into a value this file has to survive a round trip through. Such an
         * identifier is refused rather than escaped, because escaping is a second format to get
         * right and nothing here needs one.
         */
        fun isStorableMonitorId(raw: String): Boolean {
            if (raw.isEmpty() || raw.length > MAX_MONITOR_ID_LENGTH) return false
            return raw.all { character ->
                character in 'a'..'z' ||
                    character in 'A'..'Z' ||
                    character in '0'..'9' ||
                    character == '-' ||
                    character == '_' ||
                    character == '.' ||
                    character == ':'
            }
        }
    }
}

/**
 * The settings of every placed widget, in a file of its own.
 *
 * ### Why not the instance file
 *
 * The two obey **opposite** rules, and one file would mean the stricter one losing. An instance
 * file holds an address and an encrypted credential, and this project has decided it is never
 * replaced on the application's own initiative: a damaged one is reported, and only a reset the
 * user asks for throws it away. A widget file holds nothing that cannot be asked again — a kind, an
 * instance identifier, a switch and a few item identifiers — so a damaged one is **replaced with an
 * empty one** and the widgets say they are not set up. That is what [ReplaceFileCorruptionHandler]
 * does in the factory below, and it is the one line the opposite rule lives in.
 *
 * ### Why there is no index of identifiers
 *
 * The stored placements are derived from the key names rather than kept in a set beside them. An
 * index is a second place the truth lives, and two places drift: a write that updates one and not
 * the other leaves either an entry nothing can find or an identifier with nothing behind it.
 * Reading the keys has one cost, stated plainly — it depends on how the keys are named — and that
 * naming is in this file, a few lines from the code that relies on it.
 *
 * ### Exactly one of these per file
 *
 * The store behind it refuses to be a second live instance over a file another one already holds,
 * and it refuses loudly — observed on a device `[V]`: "There are multiple DataStores active for the
 * same file". So this repository is a singleton of the application, created once beside the
 * instance repository, and a screen that wants the settings asks the application for it rather than
 * building its own. The configuration step is the obvious place that temptation will arise.
 *
 * ### Reading is strict and never guesses
 *
 * An entry is either usable exactly as stored or it is not an entry at all. A kind this version
 * does not know, a missing or unusable instance identifier, more items than the ceiling allows, an
 * item identifier of an unexpected shape, or item identifiers on a widget that is not a selection —
 * each one makes the entry unusable, and an unusable entry means the widget is **not configured**.
 * There is no partial honouring: a certificate widget silently shown as a counts widget, or a
 * selection quietly cut to twenty of a hundred items, would both be the application deciding
 * something the user never asked for.
 */
class WidgetSettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /**
     * The usable settings of every placed widget, by the identifier its host assigned.
     *
     * Unusable entries are absent rather than reported. Unlike a stored instance there is nothing
     * here for the user to correct and nothing of theirs to lose: the configuration step writes a
     * whole entry at once, so the repair is to configure the widget again — which is exactly what a
     * widget reading as "not set up" asks for.
     *
     * It never throws into a collector. A file that cannot be read leaves no settings, which for
     * this file is the truth rather than a comfortable fiction; see the note on the class.
     */
    val settings: Flow<Map<Int, WidgetSettings>> = dataStore.data
        .map { preferences -> readAll(preferences) }
        .catch { failure ->
            if (failure is IOException) emit(emptyMap()) else throw failure
        }

    suspend fun settingsFor(appWidgetId: Int): WidgetSettings? = settings.first()[appWidgetId]

    /** Every placement the file holds a key for, usable or not. */
    suspend fun storedIds(): Set<Int> = try {
        storedIdsIn(dataStore.data.first())
    } catch (unreadable: IOException) {
        emptySet()
    }

    /**
     * Writes one placement's settings, replacing whatever was stored for it.
     *
     * Replacing and not merging: the configuration step asks every question at once, so a partial
     * write would leave a value from an earlier configuration standing beside the new answers. Like
     * the instance store, it reports failure instead of raising it.
     */
    suspend fun save(appWidgetId: Int, settings: WidgetSettings): StorageWriteResult =
        write { preferences ->
            preferences[kindKey(appWidgetId)] = settings.kind.name
            preferences[instanceIdKey(appWidgetId)] = settings.instanceId.value
            preferences[showNamesKey(appWidgetId)] = settings.showNames
            if (settings.monitorIds.isEmpty()) {
                preferences.remove(monitorIdsKey(appWidgetId))
            } else {
                preferences[monitorIdsKey(appWidgetId)] = settings.monitorIds
                    .joinToString(WidgetSettings.MONITOR_ID_SEPARATOR)
            }
        }

    /** Removes one placement's keys. The path for a widget the host reports as deleted. */
    suspend fun delete(appWidgetId: Int): StorageWriteResult =
        write { preferences -> preferences.removeEntry(appWidgetId) }

    /**
     * Removes every placement that is not among [placedAppWidgetIds].
     *
     * The second half of the cleanup, and the half that cannot be missed. A host's deletion
     * callback can fail to arrive — the receiver is not running, the broadcast is dropped, the
     * application was replaced in between — and what is left behind is a stored entry for a widget
     * nobody can see. This sweep collects those, and it runs from the push, because that is the one
     * moment the application already knows which widgets really exist.
     *
     * Nothing is written when there is nothing to remove. A sweep that wrote on every widget update
     * would turn a cleanup into a steady stream of file writes.
     */
    suspend fun removeOrphans(placedAppWidgetIds: Set<Int>): StorageWriteResult {
        val orphans = storedIds() - placedAppWidgetIds
        if (orphans.isEmpty()) return StorageWriteResult.DONE
        return write { preferences ->
            for (appWidgetId in orphans) preferences.removeEntry(appWidgetId)
        }
    }

    private fun MutablePreferences.removeEntry(appWidgetId: Int) {
        remove(kindKey(appWidgetId))
        remove(instanceIdKey(appWidgetId))
        remove(showNamesKey(appWidgetId))
        remove(monitorIdsKey(appWidgetId))
    }

    private suspend fun write(transform: (MutablePreferences) -> Unit): StorageWriteResult = try {
        dataStore.updateData { preferences ->
            val mutable = preferences.toMutablePreferences()
            transform(mutable)
            mutable
        }
        StorageWriteResult.DONE
    } catch (unwritable: IOException) {
        StorageWriteResult.FAILED
    }

    private fun readAll(preferences: Preferences): Map<Int, WidgetSettings> =
        storedIdsIn(preferences)
            .mapNotNull { appWidgetId ->
                read(preferences, appWidgetId)?.let { settings -> appWidgetId to settings }
            }
            .toMap()

    /**
     * One placement, or `null` when what is stored cannot be honoured as it stands.
     *
     * The `require` calls in [WidgetSettings] do the rest of the checking and arrive here as an
     * [IllegalArgumentException], exactly as a stored instance's do. Catching it is what turns a
     * stored value the current rules reject into a widget that is not configured.
     */
    private fun read(preferences: Preferences, appWidgetId: Int): WidgetSettings? {
        val kind = WidgetKind.parseOrNull(preferences[kindKey(appWidgetId)]) ?: return null
        val instanceId = InstanceId.parseOrNull(preferences[instanceIdKey(appWidgetId)].orEmpty())
            ?: return null
        val monitorIds = preferences[monitorIdsKey(appWidgetId)]
            ?.split(WidgetSettings.MONITOR_ID_SEPARATOR)
            .orEmpty()

        return try {
            WidgetSettings(
                kind = kind,
                instanceId = instanceId,
                // Absent means off, and that is not a guess: the project's rule is that names stay
                // off until the user says otherwise, so no stored answer and a stored no are the
                // same answer.
                showNames = preferences[showNamesKey(appWidgetId)] ?: false,
                monitorIds = monitorIds,
            )
        } catch (rejected: IllegalArgumentException) {
            null
        }
    }

    /**
     * The placements the file holds a key for, read from the key names.
     *
     * Only a well-formed name counts. A key whose middle segment is not a number cannot have been
     * written by this class, and reading it as a placement would mean inventing one.
     */
    private fun storedIdsIn(preferences: Preferences): Set<Int> = preferences.asMap().keys
        .mapNotNull { key -> appWidgetIdIn(key.name) }
        .toSet()

    companion object {

        private const val STORE_FILE_NAME = "widgora-widget-settings.preferences_pb"

        private const val PREFIX = "widget."

        private fun kindKey(appWidgetId: Int) = stringPreferencesKey("$PREFIX$appWidgetId.kind")

        private fun instanceIdKey(appWidgetId: Int) =
            stringPreferencesKey("$PREFIX$appWidgetId.instanceId")

        private fun showNamesKey(appWidgetId: Int) =
            booleanPreferencesKey("$PREFIX$appWidgetId.showNames")

        private fun monitorIdsKey(appWidgetId: Int) =
            stringPreferencesKey("$PREFIX$appWidgetId.monitorIds")

        /** The placement a key name belongs to, or `null` when it is not one of these keys. */
        private fun appWidgetIdIn(keyName: String): Int? {
            if (!keyName.startsWith(PREFIX)) return null
            val rest = keyName.removePrefix(PREFIX)
            val separator = rest.indexOf('.')
            if (separator <= 0) return null
            return rest.substring(0, separator).toIntOrNull()
        }

        /**
         * The ordinary way to obtain a repository.
         *
         * The corruption handler is the whole difference from the instance store: a file this
         * version cannot read is replaced with an empty one, on the spot and without asking. That
         * is sound here and would not be there — nothing in this file is the user's to lose, and
         * every answer it holds can be asked again in the configuration step.
         */
        fun create(context: Context): WidgetSettingsRepository =
            create(File(context.filesDir, STORE_FILE_NAME))

        /**
         * The same store at a file of the caller's choosing, in a scope of its choosing.
         *
         * It exists for one reason: an instrumented test has to exercise the corruption handler
         * against a real, really damaged file, and it must not do so on the file the application
         * is using. Building the store a second time inside the test would mean testing the test's
         * copy of this configuration rather than this one.
         *
         * The scope is a parameter because of the rule stated on the class: a second live store
         * over the same file raises, so the only way to read a file a previous store wrote — the
         * restart a test has to simulate — is to end that store's scope first. Nothing in the
         * application passes one.
         */
        internal fun create(
            file: File,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ): WidgetSettingsRepository {
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { file },
            )
            return WidgetSettingsRepository(store)
        }

        /** Where [create] puts the file. Named so that a test can say which file it is not. */
        internal fun fileIn(context: Context): File = File(context.filesDir, STORE_FILE_NAME)
    }
}
