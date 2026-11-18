package io.github.jellyforgedev.widgora.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.InstanceAddress
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import kotlinx.coroutines.launch

/**
 * The one screen on which an instance is set up.
 *
 * It renders what the state holder reports and calls back into it; it holds no state of its own
 * apart from which field has the focus. Every text comes from a resource.
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
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.settings_title), fontSize = 32.sp)
        Message(stringResource(R.string.settings_back_hint))

        if (state.storedEntryInvalid) {
            Message(stringResource(R.string.settings_invalid_entry))
        }

        LabelledField(
            label = stringResource(R.string.settings_display_name),
            value = state.displayName,
            onValueChange = holder::onDisplayNameChange,
            modifier = Modifier.focusRequester(firstField),
        )
        if (state.missingField == MissingField.DISPLAY_NAME) {
            Message(stringResource(R.string.settings_error_display_name_required))
        }

        LabelledField(
            label = stringResource(R.string.settings_address),
            value = state.address,
            onValueChange = holder::onAddressChange,
            keyboardType = KeyboardType.Uri,
        )
        Message(stringResource(R.string.settings_address_hint))
        state.addressRejection?.let { rejection -> Message(addressErrorText(rejection)) }

        LabelledField(
            label = stringResource(R.string.settings_api_key),
            value = state.apiKeyInput,
            onValueChange = holder::onApiKeyChange,
            masked = true,
            keyboardType = KeyboardType.Password,
        )
        Message(
            when (state.storedKey) {
                StoredKey.NONE -> stringResource(R.string.settings_api_key_new)
                StoredKey.STORED -> stringResource(R.string.settings_api_key_stored)
                StoredKey.UNREADABLE -> stringResource(R.string.settings_api_key_unreadable)
            },
        )
        when (state.missingField) {
            MissingField.API_KEY -> Message(stringResource(R.string.settings_error_api_key_required))
            MissingField.API_KEY_FOR_CHANGED_ADDRESS ->
                Message(stringResource(R.string.settings_error_api_key_for_changed_address))

            MissingField.DISPLAY_NAME, null -> Unit
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { scope.launch { holder.save() } }) {
                Text(stringResource(R.string.settings_save))
            }
            Button(onClick = { scope.launch { holder.testConnection() } }) {
                Text(stringResource(R.string.settings_test))
            }
            if (state.existing) {
                Button(onClick = holder::requestDelete) {
                    Text(stringResource(R.string.settings_delete))
                }
            }
        }

        if (state.savedJustNow) Message(stringResource(R.string.settings_saved))
        if (state.testing) Message(stringResource(R.string.settings_test_running))
        state.testOutcome?.let { outcome -> Message(testOutcomeText(outcome)) }

        if (state.confirmingDelete) {
            Message(stringResource(R.string.settings_delete_question))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { scope.launch { holder.confirmDelete() } }) {
                    Text(stringResource(R.string.settings_delete_confirm))
                }
                Button(onClick = holder::cancelDelete) {
                    Text(stringResource(R.string.settings_delete_cancel))
                }
            }
        }
    }
}

/**
 * A label above a single-line text field.
 *
 * androidx.tv:tv-material 1.1.0 provides no text field, so the basic one from Compose Foundation
 * is used and given a visible focus border of its own: on a television the user must be able to
 * see from across the room which field will receive what they type.
 */
@Composable
private fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    masked: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, fontSize = 18.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.onSurface, fontSize = 22.sp),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = if (masked) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done,
            ),
            modifier = modifier
                .fillMaxWidth()
                .onFocusChanged { focusState -> focused = focusState.isFocused }
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) colors.primary else colors.onSurface,
                )
                .padding(12.dp),
        )
    }
}

@Composable
private fun Message(text: String) {
    Text(text = text, fontSize = 16.sp)
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
