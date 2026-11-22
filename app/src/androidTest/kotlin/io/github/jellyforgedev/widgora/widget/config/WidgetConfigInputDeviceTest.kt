package io.github.jellyforgedev.widgora.widget.config

import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.InstanceConfig
import io.github.jellyforgedev.widgora.core.data.StorageWriteResult
import io.github.jellyforgedev.widgora.core.data.StoredInstance
import io.github.jellyforgedev.widgora.core.data.StoredInstances
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.core.data.WidgetSettings
import io.github.jellyforgedev.widgora.design.WidgoraTheme
import io.github.jellyforgedev.widgora.model.InstanceId
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The configuration step under the keys a remote control actually sends.
 *
 * ### What this proves and what it does not
 *
 * It proves the part no unit test can reach, because it is decided by the focus system on a real
 * display: that the screen puts the focus somewhere when it opens, that the directional pad
 * reaches every answer, and that the centre key is what changes one. The alternative to proving
 * it is a claim that the screen is operable with a remote, which is the kind of claim this
 * project does not make on its own word.
 *
 * It also proves the thing the settings screen had to work for and this screen gets by
 * construction: **there is no text field on it at all**. Nothing can open an input method,
 * because an input session is started by a focused editor and there is no editor in the tree.
 *
 * What it does not prove is how any of this looks from three metres, which belongs on the
 * checklist for the television.
 *
 * ### Why the input mode is set first
 *
 * The same reason as in the settings screen's own device test: this tablet emulator starts in
 * [InputMode.Touch], and in that mode Compose withholds focus from a focusable surface. A
 * television has no touchscreen and is in [InputMode.Keyboard] from the start, so each test asks
 * for the mode the real device is already in. It is a statement about the emulator; the same
 * request is made nowhere in the application.
 */
@RunWith(AndroidJUnit4::class)
class WidgetConfigInputDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    /** Nothing on this screen can open a keyboard, because nothing on it is an editor. */
    @Test
    fun thereIsNoTextFieldOnTheScreen() {
        start()

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    /**
     * The screen opens with the focus on the first instance.
     *
     * A screen that opens with the focus nowhere is one a remote cannot enter: the first press
     * goes wherever the focus system decides, and on a television that is how a user ends up
     * pressing keys at nothing.
     */
    @Test
    fun theFirstInstanceHasTheFocusWhenTheScreenOpens() {
        start()

        compose.onNodeWithTag(WidgetConfigTags.instanceRow(0)).assertIsFocused()
    }

    /** A second instance is one press down, and the centre key chooses it. */
    @Test
    fun aSecondInstanceIsOnePressDownAndTheCentreKeyChoosesIt() {
        val holder = start()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithTag(WidgetConfigTags.instanceRow(1)).assertIsFocused()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertEquals(OTHER_INSTANCE_ID, holder.state.value.selectedInstanceId)
    }

    /**
     * The consent is reachable and the centre key is what gives it.
     *
     * Two presses down from the first row: past the second instance, then onto the switch. The
     * labels and the explanation between them take no focus, which is what makes the count
     * predictable.
     */
    @Test
    fun theConsentIsTwoPressesDownAndTheCentreKeyGivesIt() {
        val holder = start()
        assertFalse(holder.state.value.showNames)

        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.waitForIdle()
        compose.onNodeWithTag(WidgetConfigTags.SHOW_NAMES).assertIsFocused()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertTrue(holder.state.value.showNames)
    }

    /** And it can be taken back with the same key, which is what makes it a switch. */
    @Test
    fun theConsentCanBeTakenBackWithTheSameKey() {
        val holder = start()

        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionCenter)
            pressKey(Key.DirectionCenter)
        }
        compose.waitForIdle()

        assertFalse(holder.state.value.showNames)
    }

    /**
     * Saving is reachable from the switch, and it writes what was answered.
     *
     * The button is the last stop in the order a remote walks, which is also the order a user
     * reads the screen in: choose, decide, confirm.
     */
    @Test
    fun savingIsReachableAndWritesTheAnswers() {
        val store = RecordingStore()
        start(store)

        // One press at a time, each followed by a wait. Pressing the centre key changes the
        // switch's own label, which recomposes that row — and a key sent into a tree that is
        // still being rebuilt is how the first version of this test came to find the focus
        // somewhere it had no reason to be.
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        compose.onNodeWithTag(WidgetConfigTags.SHOW_NAMES).assertIsFocused()

        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        compose.onNodeWithText(label(R.string.widget_config_save)).assertIsFocused()

        press(Key.DirectionCenter)

        assertEquals(
            WidgetSettings(
                kind = WidgetKind.STATUS,
                instanceId = INSTANCE_ID,
                showNames = true,
            ),
            store.written[APP_WIDGET_ID],
        )
    }

    /**
     * Leaving the last answer arrives at save, never at cancel.
     *
     * This test exists because of what it found. Before the button row was made a focus group
     * with a stated entry, one press down from the switch landed on **cancel** `[V]`: the two
     * buttons sit side by side, the switch above them is full width, and the focus system picks
     * the candidate nearer the middle of the screen — the second button on a wide display. A user
     * walking down the screen would have arrived at the control that discards their answers, one
     * centre press from losing them.
     *
     * It is asserted separately from the saving test so that a failure says which of the two went
     * wrong: the order, or the writing.
     */
    @Test
    fun leavingTheLastAnswerArrivesAtSaveAndNotAtCancel() {
        start()

        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionDown)

        compose.onNodeWithText(label(R.string.widget_config_save)).assertIsFocused()
        compose.onNodeWithText(label(R.string.widget_config_cancel)).assertIsNotFocused()
    }

    // --- Helpers -------------------------------------------------------------------------------

    /** One key, then a wait, because a press into a tree being rebuilt lands nowhere useful. */
    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun start(store: WidgetSettingsStore = RecordingStore()): WidgetConfigStateHolder {
        val holder = WidgetConfigStateHolder(
            appWidgetId = APP_WIDGET_ID,
            kind = WidgetKind.STATUS,
            instances = MutableStateFlow(
                StoredInstances.Readable(
                    listOf(
                        valid(INSTANCE_ID, "Kuma at home"),
                        valid(OTHER_INSTANCE_ID, "Kuma lab"),
                    ),
                ),
            ),
            settings = store,
        )

        var modes: InputModeManager? = null
        compose.setContent {
            modes = LocalInputModeManager.current
            WidgoraTheme {
                WidgetConfigScreen(holder = holder, onSaved = {}, onCancel = {})
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { checkNotNull(modes).requestInputMode(InputMode.Keyboard) }
        compose.waitForIdle()
        return holder
    }

    private fun valid(instanceId: InstanceId, displayName: String) = StoredInstance.Valid(
        InstanceConfig(
            instanceId = instanceId,
            displayName = displayName,
            baseUrl = "https://kuma.example.invalid",
            enabled = true,
        ),
    )

    /** A label as the device would show it, so that the test does not assume a language. */
    private fun label(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private class RecordingStore : WidgetSettingsStore {

        var written: Map<Int, WidgetSettings> = emptyMap()

        override suspend fun settingsFor(appWidgetId: Int): WidgetSettings? = null

        override suspend fun save(
            appWidgetId: Int,
            settings: WidgetSettings,
        ): StorageWriteResult {
            written = written + (appWidgetId to settings)
            return StorageWriteResult.DONE
        }
    }

    private companion object {

        const val APP_WIDGET_ID = 4716
        val INSTANCE_ID = InstanceId("kuma")
        val OTHER_INSTANCE_ID = InstanceId("kuma-two")
    }
}
