package io.github.jellyforgedev.widgora.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.github.jellyforgedev.widgora.BuildConfig
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.InstanceAddress
import io.github.jellyforgedev.widgora.design.WidgoraColors
import io.github.jellyforgedev.widgora.design.WidgoraFocus
import io.github.jellyforgedev.widgora.design.WidgoraOverscan
import io.github.jellyforgedev.widgora.design.WidgoraShapes
import io.github.jellyforgedev.widgora.design.WidgoraSpacing
import io.github.jellyforgedev.widgora.design.WidgoraText
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The one screen on which an instance is set up.
 *
 * It renders what the state holder reports and calls back into it; it holds no state of its own
 * apart from which field has the focus. Every text comes from a resource, every size and colour
 * from the design tokens.
 */
@Composable
fun SettingsScreen(holder: SettingsStateHolder, modifier: Modifier = Modifier) {
    val state by holder.state.collectAsState()
    val scope = rememberCoroutineScope()
    val firstField = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        holder.load()
        firstField.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Only one of these two takes effect, and which one depends on the platform version;
            // see the note on the manifest's windowSoftInputMode. Up to Android 14 the window
            // itself is resized and the keyboard inset arrives here already consumed, so this
            // adds nothing. From Android 15 the window is no longer resized and this is what
            // shortens the scrolling area instead. Applied outside the scroll on purpose: the
            // point is to make the viewport smaller, not to pad the content inside it.
            .imePadding()
            .verticalScroll(scrollState)
            .padding(
                horizontal = WidgoraOverscan.Horizontal,
                vertical = WidgoraOverscan.Vertical,
            ),
        verticalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            fontSize = WidgoraText.ScreenTitle.size,
            lineHeight = WidgoraText.ScreenTitle.lineHeight,
            color = WidgoraColors.OnSurface,
        )
        Hint(stringResource(R.string.settings_back_hint))
        Hint(stringResource(R.string.settings_field_hint))

        if (state.storedEntryInvalid) {
            Message(stringResource(R.string.settings_invalid_entry), WidgoraColors.StateError)
        }
        if (state.configurationUnreadable) {
            Message(stringResource(R.string.settings_config_unreadable), WidgoraColors.StateError)
        }
        if (state.resetJustNow) {
            Message(stringResource(R.string.settings_reset_done), WidgoraColors.StateOk)
        }

        LabelledField(
            label = stringResource(R.string.settings_display_name),
            value = state.displayName,
            onValueChange = holder::onDisplayNameChange,
            editing = state.editingField == EditableField.DISPLAY_NAME,
            anyFieldEditing = state.editingField != null,
            onStartEditing = { holder.startEditing(EditableField.DISPLAY_NAME) },
            onStopEditing = holder::stopEditing,
            scrollState = scrollState,
            testTag = SettingsTags.DISPLAY_NAME_FIELD,
            keyboardType = keyboardTypeFor(EditableField.DISPLAY_NAME),
            focusRequester = firstField,
        )
        if (state.missingField == MissingField.DISPLAY_NAME) {
            Message(
                stringResource(R.string.settings_error_display_name_required),
                WidgoraColors.StateError,
            )
        }

        LabelledField(
            label = stringResource(R.string.settings_address),
            value = state.address,
            onValueChange = holder::onAddressChange,
            editing = state.editingField == EditableField.ADDRESS,
            anyFieldEditing = state.editingField != null,
            onStartEditing = { holder.startEditing(EditableField.ADDRESS) },
            onStopEditing = holder::stopEditing,
            scrollState = scrollState,
            testTag = SettingsTags.ADDRESS_FIELD,
            keyboardType = keyboardTypeFor(EditableField.ADDRESS),
            // The example belongs in the empty field, not under it: a line that never goes away
            // reads as a statement about the value above it.
            placeholder = stringResource(R.string.settings_address_hint),
        )
        state.addressRejection?.let { rejection ->
            Message(addressErrorText(rejection), WidgoraColors.StateError)
        }

        LabelledField(
            label = stringResource(R.string.settings_api_key),
            value = state.apiKeyInput,
            onValueChange = holder::onApiKeyChange,
            editing = state.editingField == EditableField.API_KEY,
            anyFieldEditing = state.editingField != null,
            onStartEditing = { holder.startEditing(EditableField.API_KEY) },
            onStopEditing = holder::stopEditing,
            scrollState = scrollState,
            testTag = SettingsTags.API_KEY_FIELD,
            masked = !state.apiKeyVisible,
            // Two password variations, never anything else. The hidden one makes a keyboard that
            // fills the screen draw dots in its own copy of the text; the visible one lets it draw
            // the text, which is the point of revealing it. What both have in common is the part
            // that matters: a keyboard offers no suggestions for a password field and does not
            // take it into its dictionary. An earlier version used ordinary text for the revealed
            // state and produced a suggestion strip on a television — see [keyboardTypeFor].
            keyboardType = keyboardTypeFor(EditableField.API_KEY, state.apiKeyVisible),
            // Always present, never only when there is something to reveal. A button that comes
            // and goes changes the focus order under the user's thumb, which on a remote is worse
            // than a button that reveals an empty field.
            trailing = {
                Action(
                    text = stringResource(
                        if (state.apiKeyVisible) {
                            R.string.settings_api_key_hide
                        } else {
                            R.string.settings_api_key_show
                        },
                    ),
                    onClick = holder::toggleApiKeyVisible,
                )
            },
        )
        Hint(
            when (state.storedKey) {
                StoredKey.NONE -> stringResource(R.string.settings_api_key_new)
                StoredKey.STORED -> stringResource(R.string.settings_api_key_stored)
                StoredKey.UNREADABLE -> stringResource(R.string.settings_api_key_unreadable)
            },
        )
        when (state.missingField) {
            MissingField.API_KEY -> Message(
                stringResource(R.string.settings_error_api_key_required),
                WidgoraColors.StateError,
            )

            MissingField.API_KEY_FOR_CHANGED_ADDRESS -> Message(
                stringResource(R.string.settings_error_api_key_for_changed_address),
                WidgoraColors.StateError,
            )

            MissingField.DISPLAY_NAME, null -> Unit
        }

        Row(horizontalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium)) {
            Action(stringResource(R.string.settings_save)) { scope.launch { holder.save() } }
            Action(stringResource(R.string.settings_test)) {
                scope.launch { holder.testConnection() }
            }
            if (state.existing) {
                Action(stringResource(R.string.settings_delete), holder::requestDelete)
            }
            // Only offered while storage cannot be read. With a readable file there is a named
            // instance to delete instead, and throwing the whole file away would be a blunter
            // instrument than the situation calls for.
            if (state.configurationUnreadable) {
                Action(stringResource(R.string.settings_reset), holder::requestReset)
            }
            DebugWidgetHostAction()
        }

        if (state.savedJustNow) {
            Message(stringResource(R.string.settings_saved), WidgoraColors.StateOk)
        }
        state.failedWrite?.let { failure ->
            Message(failedWriteText(failure), WidgoraColors.StateError)
        }
        if (state.testing) {
            Message(stringResource(R.string.settings_test_running), WidgoraColors.StateLoading)
        }
        state.testOutcome?.let { outcome ->
            Message(testOutcomeText(outcome), testOutcomeColor(outcome))
        }

        if (state.confirmingDelete) {
            Message(stringResource(R.string.settings_delete_question), WidgoraColors.StateStale)
            Row(horizontalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium)) {
                Action(stringResource(R.string.settings_delete_confirm)) {
                    scope.launch { holder.confirmDelete() }
                }
                Action(stringResource(R.string.settings_delete_cancel), holder::cancelDelete)
            }
        }

        if (state.confirmingReset) {
            Message(stringResource(R.string.settings_reset_question), WidgoraColors.StateStale)
            Row(horizontalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium)) {
                Action(stringResource(R.string.settings_reset_confirm)) {
                    scope.launch { holder.confirmReset() }
                }
                Action(stringResource(R.string.settings_reset_cancel), holder::cancelReset)
            }
        }
    }
}

/**
 * The way into the widget host, in a debug build and nowhere else.
 *
 * ### Why this is a flag and not a source set
 *
 * The host activity and the permission it needs are already in the debug source set, which is
 * where the decision that matters is made: a release build cannot contain them, whatever this
 * function does. What is left is one row on a screen, and a screen cannot be split across source
 * sets — one of the two would have to be a second copy of this whole file, and two copies of a
 * real screen is how two versions of it come to exist.
 *
 * The alternative considered was a hook declared here and filled in by the debug build. It keeps
 * the row itself out of a release build, at the price of a settable global in production code,
 * which is a worse thing to ship than an `if` that is never true.
 *
 * ### Why the activity is named as a string
 *
 * This file is compiled into both builds, and in a release build that class does not exist, so
 * it cannot be referred to as a class. A test in the debug variant resolves the name, which is
 * what keeps a rename from turning into a dead button nobody notices.
 */
@Composable
private fun DebugWidgetHostAction() {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    Action(stringResource(R.string.settings_debug_widget_host)) {
        val intent = Intent().setClassName(context.packageName, DEBUG_WIDGET_HOST_ACTIVITY)
        runCatching { context.startActivity(intent) }
    }
}

/** The host activity of the debug build. Resolved by a test in that variant. */
internal const val DEBUG_WIDGET_HOST_ACTIVITY =
    "io.github.jellyforgedev.widgora.devhost.WidgetHostActivity"

/** The few nodes an instrumented test has to address by name rather than by what they say. */
internal object SettingsTags {

    const val DISPLAY_NAME_FIELD = "settings.field.displayName"
    const val ADDRESS_FIELD = "settings.field.address"
    const val API_KEY_FIELD = "settings.field.apiKey"
}

/**
 * The character a hidden value is drawn with.
 *
 * It is the one `PasswordVisualTransformation` uses itself, so that a field looks the same before
 * and during editing. A different character would make the value appear to change the moment
 * editing began.
 */
private const val MASK_CHARACTER = '•'

/**
 * A label above a single-line text field, in the one arrangement a remote control can operate.
 *
 * ### Why having the focus is not the same as being edited
 *
 * A Compose text field opens the on-screen keyboard as soon as it takes the focus: focus is what
 * starts the input session, and the legacy field has no switch to say otherwise —
 * [KeyboardOptions.showKeyboardOnFocus] exists in Compose 1.12.1 but is read only by the
 * state-based field's own modifier node, not by this one `[V]`. With a remote that means the
 * keyboard springs up on the way past every field, which is what it did.
 *
 * Even suppressing that would leave the second half of the problem. A focused text field consumes
 * the left and right keys to move its cursor, so a neighbouring button — the one that reveals the
 * key — could not be reached while the field had the focus.
 *
 * So a field that is not being edited is **not a text field at all**: it is a focusable surface
 * showing its value. There is no editor in the tree to open a keyboard or to swallow a key, by
 * construction rather than by a setting. [androidx.compose.foundation.clickable] already treats
 * `DirectionCenter` and `Enter` on key-up as a click `[V]`, which is the remote's OK button, and
 * that is what swaps in the real field. "Done" on the keyboard and the back key both end it
 * again, and the focus returns to the surface so that navigation can carry on.
 *
 * ### Keeping the field in sight
 *
 * An input method on a television appears in one of two shapes. As an overlay it covers the lower
 * part of the screen, and the answer to that is the screen's own: the window is shortened — by
 * `windowSoftInputMode` or by `imePadding`, depending on the platform version — and the field
 * being edited is scrolled to the top of what is left. Focus alone would not do it: that scrolls
 * a field in by the smallest amount that works, which leaves it at the bottom edge, which is
 * exactly where the keyboard is.
 *
 * In the other shape the input method fills the screen entirely and shows the text being edited in
 * its own extract view at the top, and nothing an application does can keep its own field visible
 * then. That shape is already being argued against on this screen's behalf: Compose sets
 * `IME_FLAG_NO_FULLSCREEN` on every editor it creates, without condition `[V]`, measured in the
 * bytecode of `EditorInfo.update` in Compose Foundation 1.12.1. It is a request rather than a
 * rule — an input method decides for itself — but on the television it is honoured, where the
 * keyboard appears as an overlay along the bottom `[V]`.
 *
 * Known limit `[U]`: while the keyboard is open the system gives it the back key first, so the
 * first press closes the keyboard and a second one ends the edit. Pressing "Done" does both at
 * once, which is why the hint on the screen names that way out first.
 */
@Composable
private fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    editing: Boolean,
    /** Whether *any* field of the screen is being edited, this one included. */
    anyFieldEditing: Boolean,
    onStartEditing: () -> Unit,
    onStopEditing: () -> Unit,
    scrollState: ScrollState,
    testTag: String,
    // Deliberately without a default. The obvious default would be ordinary text, which is the
    // one kind of field this screen may not have: a caller that forgot it would get a keyboard
    // that suggests and learns, and nothing would say so.
    keyboardType: KeyboardType,
    focusRequester: FocusRequester? = null,
    masked: Boolean = false,
    placeholder: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var topWithinForm by remember { mutableIntStateOf(0) }
    var wasEditing by remember { mutableStateOf(false) }
    val ownResting = remember { FocusRequester() }
    // The caller's requester, where there is one, belongs to the surface rather than to the
    // editor: the surface is what exists when the screen opens and asks for the first focus.
    val resting = focusRequester ?: ownResting
    val editor = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // A field is one line high whether it holds anything or not, so that an empty one still
    // reads as a field. The figure comes from the type token rather than from a chosen number,
    // and it follows the font scale because the token is in sp.
    val oneLine = with(LocalDensity.current) { WidgoraText.Label.lineHeight.toDp() }

    LaunchedEffect(editing) {
        if (editing) {
            editor.requestFocus()
            // Brings the field to the top of the scrolling area, which is the part of the screen
            // the keyboard leaves visible. Where the window is resized instead, the area is
            // already shorter and this still puts the field at the top of it.
            scrollState.animateScrollTo(topWithinForm)
        } else {
            keyboard?.hide()
            // Only after an edit, and deliberately not on the first composition: otherwise every
            // field would ask for the focus at startup and the last one would win.
            //
            // The second condition is about one field handing over to another. This effect and
            // the one in the field taking over both run in the same frame, in the order the
            // fields appear on screen, so a field above the one being left would ask for the
            // focus first and this line would take it straight back. A remote cannot reach that
            // — an open keyboard has the arrow keys — but a keyboard attached to the device can.
            if (wasEditing && !anyFieldEditing) resting.requestFocus()
        }
        wasEditing = editing
    }

    Column(
        modifier = Modifier.onGloballyPositioned { coordinates ->
            topWithinForm = coordinates.positionInParent().y.roundToInt()
        },
        verticalArrangement = Arrangement.spacedBy(WidgoraSpacing.Tiny),
    ) {
        Text(
            text = label,
            fontSize = WidgoraText.Label.size,
            lineHeight = WidgoraText.Label.lineHeight,
            color = WidgoraColors.OnSurface,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(WidgoraColors.Surface, WidgoraShapes.Field)
                    // While editing the mark is unconditional: the editor holds the focus because
                    // it was just asked for it, and reading that back from a callback would make
                    // the border depend on the order two nodes report a focus change in.
                    .border(
                        width = WidgoraFocus.borderWidth(editing || focused),
                        color = WidgoraFocus.borderColor(editing || focused),
                        shape = WidgoraShapes.Field,
                    )
                    .then(
                        if (editing) {
                            Modifier
                        } else {
                            Modifier
                                .onFocusChanged { state -> focused = state.isFocused }
                                .focusRequester(resting)
                                .clickable(onClick = onStartEditing)
                        },
                    )
                    .padding(WidgoraSpacing.Medium)
                    .heightIn(min = oneLine)
                    .testTag(testTag),
            ) {
                if (editing) {
                    BackHandler { onStopEditing() }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = WidgoraColors.OnSurface,
                            fontSize = WidgoraText.Label.size,
                            lineHeight = WidgoraText.Label.lineHeight,
                        ),
                        // The cursor only exists while the field is being edited, so it is part
                        // of the focus mark and takes the same colour.
                        cursorBrush = SolidColor(WidgoraColors.Focus),
                        visualTransformation = if (masked) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = keyboardType,
                            imeAction = ImeAction.Done,
                            // Nothing on this screen is prose: a name, an address and a key are
                            // all values a correction can only damage. On its own this withholds
                            // one flag and does not stop suggestions — the field's type does
                            // that, see [keyboardTypeFor] — but it is the right statement to
                            // make about these values and costs nothing.
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(onDone = { onStopEditing() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(editor),
                    )
                } else {
                    RestingValue(value = value, masked = masked, placeholder = placeholder)
                }
            }
            trailing?.invoke()
        }
    }
}

/**
 * What a field shows while it is not being edited.
 *
 * A hidden value is drawn as one [MASK_CHARACTER] per character, which is what the masked editor
 * shows as well. The value itself is in the state either way; nothing here decides whether it may
 * be seen, only how it looks.
 */
@Composable
private fun RestingValue(value: String, masked: Boolean, placeholder: String?) {
    val shown = when {
        value.isEmpty() -> placeholder.orEmpty()
        masked -> MASK_CHARACTER.toString().repeat(value.length)
        else -> value
    }
    Text(
        text = shown,
        fontSize = WidgoraText.Label.size,
        lineHeight = WidgoraText.Label.lineHeight,
        color = if (value.isEmpty()) WidgoraColors.OnSurfaceMuted else WidgoraColors.OnSurface,
    )
}

@Composable
private fun Action(text: String, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(
            text = text,
            fontSize = WidgoraText.Label.size,
            lineHeight = WidgoraText.Label.lineHeight,
        )
    }
}

@Composable
private fun Message(text: String, color: Color) {
    Text(
        text = text,
        fontSize = WidgoraText.Label.size,
        lineHeight = WidgoraText.Label.lineHeight,
        color = color,
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        fontSize = WidgoraText.Caption.size,
        lineHeight = WidgoraText.Caption.lineHeight,
        color = WidgoraColors.OnSurfaceMuted,
    )
}

@Composable
private fun addressErrorText(rejection: InstanceAddress.Rejection): String = stringResource(
    when (rejection) {
        InstanceAddress.Rejection.NOT_AN_ADDRESS ->
            R.string.settings_address_error_not_an_address

        InstanceAddress.Rejection.NOT_HTTPS -> R.string.settings_address_error_not_https
        InstanceAddress.Rejection.HOST_MISSING -> R.string.settings_address_error_host_missing
        InstanceAddress.Rejection.CREDENTIALS_IN_ADDRESS ->
            R.string.settings_address_error_credentials_in_address

        InstanceAddress.Rejection.QUERY_NOT_ALLOWED ->
            R.string.settings_address_error_query_not_allowed

        InstanceAddress.Rejection.FRAGMENT_NOT_ALLOWED ->
            R.string.settings_address_error_fragment_not_allowed
    },
)

/**
 * Why nothing was written, in terms of what the user was trying to do.
 *
 * None of the three messages repeats a message from the platform. What the file system said is of
 * no use to the person holding the remote, and it is also the kind of text that carries a path.
 */
@Composable
private fun failedWriteText(failure: FailedWrite): String = stringResource(
    when (failure) {
        FailedWrite.SAVE -> R.string.settings_error_save_failed
        FailedWrite.DELETE -> R.string.settings_error_delete_failed
        FailedWrite.RESET -> R.string.settings_error_reset_failed
    },
)

/** Named outcomes only; no monitor name and nothing the server wrote reaches the screen. */
@Composable
private fun testOutcomeText(outcome: ConnectionTestResult): String = when (outcome) {
    is ConnectionTestResult.Reachable ->
        stringResource(R.string.settings_test_reachable, outcome.monitorCount)

    ConnectionTestResult.AddressRejected ->
        stringResource(R.string.settings_test_address_rejected)

    is ConnectionTestResult.Failed -> stringResource(
        when (outcome.reason) {
            Reason.UNAUTHORIZED -> R.string.settings_test_unauthorized
            Reason.ENDPOINT_NOT_FOUND -> R.string.settings_test_endpoint_not_found
            Reason.UNREACHABLE -> R.string.settings_test_unreachable
            Reason.MALFORMED_RESPONSE -> R.string.settings_test_malformed
            Reason.OVERSIZED -> R.string.settings_test_oversized
            Reason.UNKNOWN -> R.string.settings_test_unknown
        },
    )
}

private fun testOutcomeColor(outcome: ConnectionTestResult): Color = when (outcome) {
    is ConnectionTestResult.Reachable -> WidgoraColors.StateOk
    else -> WidgoraColors.StateError
}
