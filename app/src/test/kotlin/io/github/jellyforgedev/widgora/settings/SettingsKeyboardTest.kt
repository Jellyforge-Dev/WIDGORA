package io.github.jellyforgedev.widgora.settings

import androidx.compose.ui.text.input.KeyboardType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsKeyboardTest {

    /**
     * The rule, not the three answers.
     *
     * Every field of this screen, in every state it can be in, has to ask for a type a keyboard
     * neither completes nor remembers. Written as a loop over all of them so that a fourth field
     * cannot be added with an ordinary text keyboard and go unnoticed.
     */
    @Test
    fun noFieldEverAsksForAKeyboardThatSuggestsOrLearns() {
        for (field in EditableField.entries) {
            for (apiKeyVisible in listOf(false, true)) {
                val type = keyboardTypeFor(field, apiKeyVisible)
                assertTrue(
                    type in NoSuggestionKeyboardTypes,
                    "$field with apiKeyVisible=$apiKeyVisible asked for $type",
                )
            }
        }
    }

    /**
     * Revealing the key changes which password variation it is, never whether it is one.
     *
     * The earlier version asked for ordinary text once the key was revealed, so that a keyboard
     * filling the screen would show the text rather than dots. It did show the text — and offered
     * suggestions for it. The visible password variation gives the first without the second.
     */
    @Test
    fun theKeyFieldIsAPasswordWhetherOrNotItIsRevealed() {
        assertEquals(
            KeyboardType.Password,
            keyboardTypeFor(EditableField.API_KEY, apiKeyVisible = false),
        )
        assertEquals(
            KeyboardType.PasswordVisible,
            keyboardTypeFor(EditableField.API_KEY, apiKeyVisible = true),
        )
    }

    /**
     * The name and the address are readable and still not learnable.
     *
     * The address gives up the URI keyboard layout for this. It can name an internal host, which
     * is the kind of value that must not end up in a dictionary; whether the key field happens to
     * be revealed has nothing to do with either of them.
     */
    @Test
    fun theNameAndTheAddressAreVisibleButNotLearnable() {
        for (field in listOf(EditableField.DISPLAY_NAME, EditableField.ADDRESS)) {
            for (apiKeyVisible in listOf(false, true)) {
                assertEquals(
                    KeyboardType.PasswordVisible,
                    keyboardTypeFor(field, apiKeyVisible),
                    "$field with apiKeyVisible=$apiKeyVisible",
                )
            }
        }
    }
}
