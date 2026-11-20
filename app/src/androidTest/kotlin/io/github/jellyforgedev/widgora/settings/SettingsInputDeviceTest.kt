package io.github.jellyforgedev.widgora.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.InstanceConfig
import io.github.jellyforgedev.widgora.core.data.SecretState
import io.github.jellyforgedev.widgora.core.data.StorageWriteResult
import io.github.jellyforgedev.widgora.core.data.StoredInstances
import io.github.jellyforgedev.widgora.design.WidgoraTheme
import io.github.jellyforgedev.widgora.model.InstanceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings screen under the keys a remote control actually sends.
 *
 * ### What this proves and what it does not
 *
 * It proves the three things a unit test cannot reach, because they are decisions the focus system
 * and the key routing make on a real display: that arriving at a field leaves no editor in the
 * tree, that the centre key starts one, and that the button beside the key field can be reached
 * with one press to the right.
 *
 * It does **not** prove that the on-screen keyboard stays shut, and it deliberately does not
 * pretend to. Whether an input method appears is up to that input method, and an emulator with a
 * hardware keyboard attached may never show one at all, which would make such an assertion pass
 * while saying nothing. What is asserted instead is the condition the keyboard depends on: an
 * input session is started by a focused text field, so a screen with no text field in it has
 * nothing that could open one. The keyboard itself belongs on the checklist for the television.
 *
 * ### Why the input mode is set before anything else
 *
 * Measured on the tablet emulator these tests run on `[V]`: it starts in [InputMode.Touch], and in
 * that mode **nothing on this screen can take the focus** — not by navigation and not even on an
 * explicit request. Compose withholds focus from a focusable surface while the user is understood
 * to be touching the screen, which is the right behaviour on a telephone and has nothing to do
 * with the screen under test. A television has no touchscreen and is in [InputMode.Keyboard] from
 * the start, so the first thing each test does is ask for the mode the real device is already in.
 * It is a statement about the emulator, not a workaround in the screen: the same request is not
 * made anywhere in the application.
 */
@RunWith(AndroidJUnit4::class)
class SettingsInputDeviceTest {

    /**
     * The newer of the two rules. The older one is deprecated in Compose 1.12.1 in favour of this
     * one, which queues coroutines on a standard dispatcher instead of running them immediately —
     * closer to how they behave in the application, and the reason every step below waits for the
     * screen to settle rather than assuming it already has.
     */
    @get:Rule
    val compose = createComposeRule()

    /**
     * Arriving at a field must not put an editor on the screen.
     *
     * The first field is focused by the screen itself, which is the moment the old version opened
     * the keyboard. The count is taken over the whole tree rather than at one field, so a second
     * field quietly behaving differently would fail here too.
     */
    @Test
    fun theFocusedFieldIsNotAnEditor() {
        val holder = start()

        compose.onNodeWithTag(SettingsTags.DISPLAY_NAME_FIELD).assertIsFocused()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertNull(holder.state.value.editingField)
    }

    /** The centre key of a remote — [Key.DirectionCenter] — is what begins an edit. */
    @Test
    fun theCentreKeyStartsAnEdit() {
        val holder = start()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertEquals(EditableField.DISPLAY_NAME, holder.state.value.editingField)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }

    /**
     * The button that reveals the key is reachable, and reaching it is one press.
     *
     * This is the test the change exists for as much as for the keyboard: an editor holding the
     * focus consumes the left and right keys for its cursor, and a button beside it would then be
     * unreachable. Two presses down move from the name to the address and on to the key field —
     * the labels and hints between them take no focus.
     */
    @Test
    fun theRevealButtonIsOnePressToTheRightOfTheKeyField() {
        val holder = start()

        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.API_KEY_FIELD).assertIsFocused()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.settings_api_key_show)).assertIsFocused()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertTrue(holder.state.value.apiKeyVisible)
        // The field was never edited, so nothing was typed and there is nothing to show. What is
        // asserted is the switch, not a value.
        assertNull(holder.state.value.editingField)
        compose.onNodeWithText(label(R.string.settings_api_key_hide)).assertIsFocused()
    }

    /**
     * Beginning an edit moves the field up, not merely far enough.
     *
     * Focus alone already scrolls a field into view, but only by the smallest amount that works —
     * which leaves it at the bottom edge, exactly where the keyboard is. So the screen scrolls it
     * to the top of what is left, and this is the assertion that it does: the field has to end up
     * higher than where focusing it put it.
     *
     * The content is given a short box on purpose. On a full television screen the whole form fits
     * and there is nothing to scroll, so the test would pass without the mechanism it is about.
     */
    @Test
    fun beginningAnEditMovesTheFieldUpwards() {
        val holder = start(height = 300)

        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.waitForIdle()
        val afterFocusing = compose.onNodeWithTag(SettingsTags.API_KEY_FIELD).getBoundsInRoot()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        val afterEditing = compose.onNodeWithTag(SettingsTags.API_KEY_FIELD).getBoundsInRoot()

        assertEquals(EditableField.API_KEY, holder.state.value.editingField)
        assertTrue(
            "field top before=${afterFocusing.top} after=${afterEditing.top}",
            afterEditing.top < afterFocusing.top,
        )
    }

    /**
     * Puts the screen on the display and returns its state holder.
     *
     * @param height the height in dp the screen is given, or `null` for the whole display.
     */
    private fun start(height: Int? = null): SettingsStateHolder {
        val holder = SettingsStateHolder(EmptyStore(), ReachableTester)
        var modes: InputModeManager? = null
        compose.setContent {
            modes = LocalInputModeManager.current
            WidgoraTheme {
                if (height == null) {
                    SettingsScreen(holder)
                } else {
                    Box(modifier = Modifier.height(height.dp)) { SettingsScreen(holder) }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { checkNotNull(modes).requestInputMode(InputMode.Keyboard) }
        compose.waitForIdle()
        return holder
    }

    /** A label as the device would show it, so that the test does not assume a language. */
    private fun label(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    /** Nothing stored and nothing storable: this test is about keys, not about persistence. */
    private class EmptyStore : InstanceStore {

        override val instances: Flow<StoredInstances> =
            MutableStateFlow(StoredInstances.Readable(emptyList()))

        override suspend fun secretOf(instanceId: InstanceId): SecretState = SecretState.Missing

        override suspend fun save(config: InstanceConfig, apiKey: String?): StorageWriteResult =
            StorageWriteResult.DONE

        override suspend fun delete(instanceId: InstanceId): StorageWriteResult =
            StorageWriteResult.DONE

        override suspend fun reset(): StorageWriteResult = StorageWriteResult.DONE
    }

    private object ReachableTester : ConnectionTester {

        override suspend fun test(baseUrl: String, apiKey: String): ConnectionTestResult =
            ConnectionTestResult.Reachable(0)
    }
}
