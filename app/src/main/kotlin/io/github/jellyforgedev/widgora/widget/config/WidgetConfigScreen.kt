package io.github.jellyforgedev.widgora.widget.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.design.WidgoraColors
import io.github.jellyforgedev.widgora.design.WidgoraFocus
import io.github.jellyforgedev.widgora.design.WidgoraOverscan
import io.github.jellyforgedev.widgora.design.WidgoraShapes
import io.github.jellyforgedev.widgora.design.WidgoraSpacing
import io.github.jellyforgedev.widgora.design.WidgoraText
import kotlinx.coroutines.launch

/**
 * The one screen on which a placed widget is set up, whatever its type.
 *
 * ### Why one screen and not three
 *
 * Three screens would be three places for the same focus handling, the same instance list and the
 * same confirmation to drift apart. The type decides which questions appear and nothing else; the
 * two types that do not exist yet need no new screen, only a new section — which is why the
 * sections are written as a `when` over the type rather than as one body with the others bolted
 * on later.
 *
 * ### Why there is not a single text field on it
 *
 * There is nothing here to type. Every answer is a choice between things that already exist: an
 * instance the application was told about, and a yes or no. That is what makes this screen
 * operable from a remote without the compromises the settings screen had to make — no on-screen
 * keyboard appears at any point, so none of the focus trouble a keyboard brings can arise.
 *
 * Everything is reachable with the directional pad and confirmed with OK: each row is a focusable
 * surface with [androidx.compose.foundation.clickable], which already treats the centre key as a
 * click, and the focus mark is the project's own single token rather than a variant invented here.
 */
@Composable
fun WidgetConfigScreen(
    holder: WidgetConfigStateHolder,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by holder.state.collectAsState()
    val scope = rememberCoroutineScope()
    val firstRow = remember { FocusRequester() }
    val saveButton = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) { holder.load() }

    // Asked for once the list exists. Asking before that would put the focus on a row that is
    // about to be replaced, and the focus does not follow a replaced node.
    LaunchedEffect(state.loaded, state.instances.size) {
        if (state.loaded && state.instances.isNotEmpty()) firstRow.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(
                horizontal = WidgoraOverscan.Horizontal,
                vertical = WidgoraOverscan.Vertical,
            ),
        verticalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium),
    ) {
        Text(
            text = stringResource(R.string.widget_config_title),
            fontSize = WidgoraText.ScreenTitle.size,
            lineHeight = WidgoraText.ScreenTitle.lineHeight,
            color = WidgoraColors.OnSurface,
        )
        Caption(stringResource(kindLabel(state.kind)))
        Caption(stringResource(R.string.widget_config_hint))

        if (state.configurationUnreadable) {
            Message(stringResource(R.string.dashboard_config_unreadable), WidgoraColors.StateError)
        }

        /*
         * Where "down" goes from the last answer on the screen, stated rather than left to the
         * focus system's geometry.
         *
         * Measured on an emulator `[V]`: without this, one press down from the last answer landed
         * on **cancel**. The two buttons sit side by side, the answer above them is full width,
         * and the focus system picks the candidate nearer the middle of the screen — the second
         * button on a wide display. A user walking down the screen would have arrived at the
         * control that discards their answers, one centre press from losing them.
         *
         * It points at nothing in particular when there is nothing to save, because then there is
         * no save button to point at.
         */
        val afterTheLastAnswer: Modifier = Modifier.focusProperties {
            down = if (state.canSave) saveButton else FocusRequester.Default
        }
        val switchIsShown = state.kind != WidgetKind.SELECTION

        Label(stringResource(R.string.widget_config_instance))
        if (state.instances.isEmpty()) {
            Message(
                stringResource(R.string.widget_config_no_instance),
                WidgoraColors.OnSurfaceMuted,
            )
        } else {
            state.instances.forEachIndexed { index, choice ->
                ChoiceRow(
                    text = choice.label,
                    chosen = choice.instanceId == state.selectedInstanceId,
                    onClick = { holder.selectInstance(choice.instanceId) },
                    testTag = WidgetConfigTags.instanceRow(index),
                    focusRequester = firstRow.takeIf { index == 0 },
                    // The last instance is the last answer only where no switch follows it.
                    modifier = if (!switchIsShown && index == state.instances.lastIndex) {
                        afterTheLastAnswer
                    } else {
                        Modifier
                    },
                )
            }
        }

        // The consent question, for the two types that show names as an option. For the selection
        // type the selection itself is the consent, so a switch would be a second answer to a
        // question already answered — see SECURITY.md.
        when (state.kind) {
            WidgetKind.STATUS, WidgetKind.CERTIFICATES -> {
                Label(stringResource(R.string.widget_config_show_names))
                ChoiceRow(
                    text = stringResource(
                        if (state.showNames) {
                            R.string.widget_config_on
                        } else {
                            R.string.widget_config_off
                        },
                    ),
                    chosen = state.showNames,
                    onClick = { holder.setShowNames(!state.showNames) },
                    testTag = WidgetConfigTags.SHOW_NAMES,
                    modifier = afterTheLastAnswer,
                )
                Caption(stringResource(R.string.widget_config_show_names_hint))
            }

            WidgetKind.SELECTION -> Unit
        }

        Row(horizontalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium)) {
            if (state.canSave) {
                Action(
                    text = stringResource(R.string.widget_config_save),
                    focusRequester = saveButton,
                ) {
                    scope.launch { if (holder.save()) onSaved() }
                }
            }
            Action(text = stringResource(R.string.widget_config_cancel), onClick = onCancel)
        }

        if (state.saveFailed) {
            Message(stringResource(R.string.widget_config_save_failed), WidgoraColors.StateError)
        }
    }
}

/**
 * One answer, focusable and confirmed with OK.
 *
 * The chosen one is marked by a character rather than by colour alone, for the same reason every
 * state in this application carries a mark: a viewer who cannot tell the two colours apart still
 * has to be able to see which answer is in force.
 */
@Composable
private fun ChoiceRow(
    text: String,
    chosen: Boolean,
    onClick: () -> Unit,
    testTag: String,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val own = remember { FocusRequester() }
    val requester = focusRequester ?: own

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(WidgoraColors.Surface, WidgoraShapes.Field)
            .border(
                width = WidgoraFocus.borderWidth(focused),
                color = WidgoraFocus.borderColor(focused),
                shape = WidgoraShapes.Field,
            )
            .onFocusChanged { focusState -> focused = focusState.isFocused }
            .focusRequester(requester)
            .clickable(onClick = onClick)
            .padding(WidgoraSpacing.Medium)
            .testTag(testTag),
        horizontalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (chosen) CHOSEN_MARK else UNCHOSEN_MARK,
            fontSize = WidgoraText.Label.size,
            lineHeight = WidgoraText.Label.lineHeight,
            color = if (chosen) WidgoraColors.OnSurface else WidgoraColors.OnSurfaceMuted,
        )
        Text(
            text = text,
            fontSize = WidgoraText.Label.size,
            lineHeight = WidgoraText.Label.lineHeight,
            color = WidgoraColors.OnSurface,
        )
    }
}

@Composable
private fun Action(
    text: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = focusRequester?.let { requester ->
            Modifier.focusRequester(requester)
        } ?: Modifier,
    ) {
        Text(
            text = text,
            fontSize = WidgoraText.Label.size,
            lineHeight = WidgoraText.Label.lineHeight,
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        fontSize = WidgoraText.Label.size,
        lineHeight = WidgoraText.Label.lineHeight,
        color = WidgoraColors.OnSurface,
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        fontSize = WidgoraText.Caption.size,
        lineHeight = WidgoraText.Caption.lineHeight,
        color = WidgoraColors.OnSurfaceMuted,
    )
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

private fun kindLabel(kind: WidgetKind): Int = when (kind) {
    WidgetKind.STATUS -> R.string.widget_config_kind_status
    WidgetKind.CERTIFICATES -> R.string.widget_config_kind_certificates
    WidgetKind.SELECTION -> R.string.widget_config_kind_selection
}

/**
 * The marks for a chosen and an unchosen answer.
 *
 * Plain characters from the text font, as everywhere else in this application, and deliberately
 * not from the Unicode block the five monitor marks are drawn from — a shape that reads as a
 * monitor state has no business marking a chosen row.
 */
private const val CHOSEN_MARK = "✓"
private const val UNCHOSEN_MARK = "·"

/** The few nodes an instrumented test has to address by name rather than by what they say. */
internal object WidgetConfigTags {

    const val SHOW_NAMES = "widgetConfig.showNames"

    fun instanceRow(index: Int): String = "widgetConfig.instance.$index"
}
