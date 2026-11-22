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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.core.data.WidgetSettings
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
 * same confirmation to drift apart. The type decides which questions appear and nothing else, and
 * the sections are written as a `when` over the type so that a type gaining a question is a section
 * here rather than a screen of its own.
 *
 * ### Why there is not a single text field on it
 *
 * There is nothing here to type. Every answer is a choice between things that already exist: an
 * instance the application was told about, a yes or no, and monitors the instance reported. That is
 * what makes this screen operable from a remote without the compromises the settings screen had to
 * make — no on-screen keyboard appears at any point, so none of the focus trouble a keyboard brings
 * can arise.
 *
 * Everything is reachable with the directional pad and confirmed with OK: each row is a focusable
 * surface with [androidx.compose.foundation.clickable], which already treats the centre key as a
 * click, and the focus mark is the project's own single token rather than a variant invented here.
 *
 * ### The one thing on this screen that takes time
 *
 * A selection widget needs the monitors of the chosen instance, and the only party that knows them
 * is the instance. So that section reads them, which is a network call, and says which of the four
 * things is true while it does — asking, failed, none, or here they are. A screen that showed an
 * empty list for all four would be a screen that lies in three of them.
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

    // The monitors belong to the chosen instance, so they are read when that answer appears and
    // again whenever it changes. Keyed on the identifier rather than requested from the holder's
    // own load, so that choosing another instance cannot leave the previous server's list standing.
    val instanceId = state.selectedInstanceId
    LaunchedEffect(state.kind, instanceId) {
        if (state.kind == WidgetKind.SELECTION && instanceId != null) {
            holder.loadMonitors(instanceId)
        }
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
        Label(stringResource(R.string.widget_config_instance))
        if (state.instances.isEmpty()) {
            Message(
                stringResource(R.string.widget_config_no_instance),
                WidgoraColors.OnSurfaceMuted,
            )
        } else {
            val monitorRows = (state.monitors as? MonitorCatalogue.Monitors)?.choices?.size ?: 0
            val somethingFollows = when (state.kind) {
                WidgetKind.STATUS -> true
                WidgetKind.CERTIFICATES -> false
                WidgetKind.SELECTION -> monitorRows > 0
            }
            state.instances.forEachIndexed { index, choice ->
                ChoiceRow(
                    text = choice.label,
                    chosen = choice.instanceId == state.selectedInstanceId,
                    onClick = { holder.selectInstance(choice.instanceId) },
                    testTag = WidgetConfigTags.instanceRow(index),
                    focusRequester = firstRow.takeIf { index == 0 },
                    modifier = if (!somethingFollows && index == state.instances.lastIndex) {
                        afterTheLastAnswer
                    } else {
                        Modifier
                    },
                )
            }
        }

        when (state.kind) {
            // The consent as a question, for the one kind that can show its figures without
            // naming anything.
            WidgetKind.STATUS -> {
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

            // The consent as a statement. A certificates widget lists the hosts its certificates
            // belong to, so a version of it that may not name them would have nothing to show —
            // there is no second answer to offer, and pressing save is the agreement.
            WidgetKind.CERTIFICATES ->
                Caption(stringResource(R.string.widget_config_names_shown))

            // The consent as the selection itself.
            WidgetKind.SELECTION -> MonitorSection(
                state = state,
                holder = holder,
                afterTheLastAnswer = afterTheLastAnswer,
                onRetry = {
                    val id = state.selectedInstanceId
                    if (id != null) scope.launch { holder.loadMonitors(id) }
                },
            )
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
 * The monitors, and which of the four things is true about them.
 *
 * The count of what is picked sits above the list rather than below it: on a list of thirty-five
 * rows the line below would be off the screen exactly when the limit becomes interesting.
 */
@Composable
private fun MonitorSection(
    state: WidgetConfigState,
    holder: WidgetConfigStateHolder,
    afterTheLastAnswer: Modifier,
    onRetry: () -> Unit,
) {
    Label(stringResource(R.string.widget_config_monitors))
    Caption(
        stringResource(
            R.string.widget_config_monitors_hint,
            WidgetSettings.MAX_SELECTED_MONITORS,
        ),
    )

    when (val monitors = state.monitors) {
        MonitorCatalogue.Idle, MonitorCatalogue.Loading ->
            Message(
                stringResource(R.string.widget_config_monitors_loading),
                WidgoraColors.OnSurfaceMuted,
            )

        MonitorCatalogue.Unavailable -> {
            Message(
                stringResource(R.string.widget_config_monitors_failed),
                WidgoraColors.StateError,
            )
            Action(text = stringResource(R.string.widget_config_retry), onClick = onRetry)
        }

        is MonitorCatalogue.Monitors -> {
            if (monitors.choices.isEmpty()) {
                Message(
                    stringResource(R.string.widget_config_monitors_empty),
                    WidgoraColors.OnSurfaceMuted,
                )
                return
            }

            Caption(
                pluralStringResource(
                    R.plurals.widget_config_picked,
                    state.selectedMonitorIds.size,
                    state.selectedMonitorIds.size,
                ),
            )
            if (state.pickRefused) {
                Message(
                    stringResource(
                        R.string.widget_config_monitors_limit,
                        WidgetSettings.MAX_SELECTED_MONITORS,
                    ),
                    WidgoraColors.StateStale,
                )
            }

            monitors.choices.forEachIndexed { index, choice ->
                ChoiceRow(
                    text = choice.label,
                    chosen = choice.monitorId in state.selectedMonitorIds,
                    onClick = { holder.toggleMonitor(choice.monitorId) },
                    testTag = WidgetConfigTags.monitorRow(index),
                    modifier = if (index == monitors.choices.lastIndex) {
                        afterTheLastAnswer
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

/**
 * One answer, focusable and confirmed with OK.
 *
 * The chosen one is marked by a character rather than by colour alone, for the same reason every
 * state in this application carries a mark: a viewer who cannot tell the two colours apart still
 * has to be able to see which answer is in force. The same row serves a single choice and a
 * multiple one — on a list of monitors several of them carry the mark at once, which is what being
 * picked looks like.
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
 * monitor state has no business marking a chosen row, least of all on a screen listing monitors.
 */
private const val CHOSEN_MARK = "✓"
private const val UNCHOSEN_MARK = "·"

/** The few nodes an instrumented test has to address by name rather than by what they say. */
internal object WidgetConfigTags {

    const val SHOW_NAMES = "widgetConfig.showNames"

    fun instanceRow(index: Int): String = "widgetConfig.instance.$index"

    fun monitorRow(index: Int): String = "widgetConfig.monitor.$index"
}
