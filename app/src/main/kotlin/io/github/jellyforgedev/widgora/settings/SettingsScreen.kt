package io.github.jellyforgedev.widgora.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.InstanceAddress
import io.github.jellyforgedev.widgora.design.WidgoraColors
import io.github.jellyforgedev.widgora.design.WidgoraFocus
import io.github.jellyforgedev.widgora.design.WidgoraOverscan
import io.github.jellyforgedev.widgora.design.WidgoraShapes
import io.github.jellyforgedev.widgora.design.WidgoraSpacing
import io.github.jellyforgedev.widgora.design.WidgoraText
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
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

    LaunchedEffect(Unit) {
        holder.load()
        firstField.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = WidgoraOverscan.Horizontal,
                vertical = WidgoraOverscan.Vertical,
            ),
        verticalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            fontSize = WidgoraText.ScreenTitle,
            color = WidgoraColors.OnSurface,
        )
        Hint(stringResource(R.string.settings_back_hint))

        if (state.storedEntryInvalid) {
            Message(stringResource(R.string.settings_invalid_entry), WidgoraColors.StateError)
        }

        LabelledField(
            label = stringResource(R.string.settings_display_name),
            value = state.displayName,
            onValueChange = holder::onDisplayNameChange,
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
            keyboardType = KeyboardType.Uri,
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
            masked = true,
            keyboardType = KeyboardType.Password,
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
        }

        if (state.savedJustNow) {
            Message(stringResource(R.string.settings_saved), WidgoraColors.StateOk)
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
    }
}

/**
 * A label above a single-line text field.
 *
 * androidx.tv:tv-material provides no text field, so the basic one from Compose Foundation is
 * used and given a visible focus mark of its own: on a television the user must be able to see
 * from across the room which field will receive what they type.
 */
@Composable
private fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester? = null,
    masked: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
) {
    var focused by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(WidgoraSpacing.Tiny)) {
        Text(text = label, fontSize = WidgoraText.Label, color = WidgoraColors.OnSurface)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(WidgoraColors.Surface, WidgoraShapes.Field)
                .border(
                    width = if (focused) {
                        WidgoraFocus.BorderWidth
                    } else {
                        WidgoraFocus.RestingBorderWidth
                    },
                    color = if (focused) WidgoraColors.BrandViolet else WidgoraColors.Outline,
                    shape = WidgoraShapes.Field,
                )
                .padding(WidgoraSpacing.Medium),
        ) {
            if (value.isEmpty() && placeholder != null) {
                Text(
                    text = placeholder,
                    fontSize = WidgoraText.Label,
                    color = WidgoraColors.OnSurfaceMuted,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = WidgoraColors.OnSurface,
                    fontSize = WidgoraText.Label,
                ),
                cursorBrush = SolidColor(WidgoraColors.BrandViolet),
                visualTransformation = if (masked) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (focusRequester == null) {
                            Modifier
                        } else {
                            Modifier.focusRequester(focusRequester)
                        },
                    )
                    .onFocusChanged { focusState -> focused = focusState.isFocused },
            )
        }
    }
}

@Composable
private fun Action(text: String, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(text = text, fontSize = WidgoraText.Label)
    }
}

@Composable
private fun Message(text: String, color: Color) {
    Text(text = text, fontSize = WidgoraText.Label, color = color)
}

@Composable
private fun Hint(text: String) {
    Text(text = text, fontSize = WidgoraText.Caption, color = WidgoraColors.OnSurfaceMuted)
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
