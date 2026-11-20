package io.github.jellyforgedev.widgora.settings

import androidx.compose.ui.text.input.KeyboardType

/**
 * What each field of the settings screen tells the on-screen keyboard it is.
 *
 * ### Why every field here is a password variation
 *
 * A keyboard does two things with an ordinary text field that this screen cannot allow: it offers
 * word suggestions, and it takes what is typed into its own dictionary. Observed on a television:
 * typing an API key into a field declared as ordinary text produced a suggestion strip, which
 * means the key was on its way into the keyboard's vocabulary. A monitor's address is the same
 * kind of value — it can name an internal host — and the instance name is whatever the user chose
 * to call their server.
 *
 * Android has exactly one instrument for this that Compose can reach. Measured from the bytecode
 * of `EditorInfo.update` in Compose Foundation 1.12.1 `[V]`, the mapping is:
 *
 * | [KeyboardType] | `EditorInfo.inputType` | |
 * |---|---|---|
 * | `Text` | `0x01` | `TYPE_CLASS_TEXT`, no variation |
 * | `Uri` | `0x11` | `… or TYPE_TEXT_VARIATION_URI` |
 * | `Password` | `0x81` | `… or TYPE_TEXT_VARIATION_PASSWORD` |
 * | `PasswordVisible` | `0x91` | `… or TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` |
 *
 * and `autoCorrectEnabled = false` only withholds `TYPE_TEXT_FLAG_AUTO_CORRECT`. Neither
 * `TYPE_TEXT_FLAG_NO_SUGGESTIONS` nor `IME_FLAG_NO_PERSONALIZED_LEARNING` appears anywhere in that
 * method, and `PlatformImeOptions` carries only a private option string `[V]`, so neither of those
 * two flags can be set from Compose. The one `imeOptions` flag that method does set, every time
 * and without condition, is `IME_FLAG_NO_FULLSCREEN` `[V]` — which is why the extract view of a
 * full-screen keyboard is an unlikely shape here rather than the normal one.
 *
 * What is left to work with is the password variations, which a keyboard treats as values it must
 * neither complete nor remember.
 *
 * ### What it costs
 *
 * The address field gives up the URI keyboard layout — the one with `/` and `.com` within reach.
 * That is a convenience; the alternative was a keyboard that may keep a copy of an internal host
 * name. The visible variation keeps the text readable, both in the field and in the extract view
 * of a keyboard that fills the screen, so revealing the key still shows it.
 *
 * Unverified `[U]`: how a given keyboard renders a visible-password field. Withholding suggestions
 * for a password variation is the behaviour of the keyboards the platform ships, but it is the
 * keyboard's behaviour and not something the platform enforces. The device check is in
 * SECURITY.md.
 */
internal fun keyboardTypeFor(field: EditableField, apiKeyVisible: Boolean = false): KeyboardType =
    if (field == EditableField.API_KEY && !apiKeyVisible) {
        // The one field that is also hidden in its own right. The keyboard is told so, which is
        // what makes an extract view draw dots as well.
        KeyboardType.Password
    } else {
        KeyboardType.PasswordVisible
    }

/**
 * The only two types this screen may ever ask for.
 *
 * Named here so that a test can hold the rule itself rather than the three answers: whatever
 * [keyboardTypeFor] is given, what comes out has to be a variation a keyboard does not learn from.
 */
internal val NoSuggestionKeyboardTypes: Set<KeyboardType> =
    setOf(KeyboardType.Password, KeyboardType.PasswordVisible)
