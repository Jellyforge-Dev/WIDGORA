package io.github.jellyforgedev.widgora.design

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.tv.material3.Text
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.InstanceNotice
import io.github.jellyforgedev.widgora.core.data.MonitorCounts
import io.github.jellyforgedev.widgora.core.data.MonitorEntry
import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.core.data.MonitorState
import io.github.jellyforgedev.widgora.core.data.WidgetView
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaSnapshotMapper
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import io.github.jellyforgedev.widgora.model.WidgetSnapshot
import java.util.Date

/**
 * One tile. It shows every display state and every notice, reading what it needs from [view].
 *
 * The counts are drawn from the structured counts in [WidgetView.overview], never from the
 * snapshot's text. That text exists for a host launcher which has no access to this data; parsing
 * it back here would make the tile depend on a format meant for somebody else.
 *
 * What the centre key does depends on the tile; [TileAction] decides it, and a tile with nothing to
 * open is not focusable at all. The focus mark is a thick border in [WidgoraColors.Focus] **and** a
 * slight enlargement, because a remote gives no pointer and one of the two marks has to survive a
 * viewer who cannot tell the focused border colour from the resting one. The enlargement needs
 * [WidgoraFocus.ScaleHeadroom] of room beside the tile, which the row holding tiles supplies.
 */
@Composable
fun WidgetTile(
    view: WidgetView,
    onOpenDetails: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = WidgetPresentation.of(view.state)
    val snapshot = view.snapshot
    val overview = view.overview
    val action = TileAction.of(view)
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .width(WidgoraSizes.Tile)
            .scale(if (focused) WidgoraFocus.Scale else 1f)
            .background(WidgoraColors.Surface, WidgoraShapes.Tile)
            .border(
                width = WidgoraFocus.borderWidth(focused),
                color = WidgoraFocus.borderColor(focused),
                shape = WidgoraShapes.Tile,
            )
            // Before the clickable, so that it observes the focus target the clickable creates.
            .onFocusChanged { focusState -> focused = focusState.isFocused }
            .then(
                when (action) {
                    // No clickable at all, which is also what leaves the tile out of the focus
                    // order. A tile that takes the focus and then does nothing reads as a fault.
                    TileAction.NONE -> Modifier

                    TileAction.OPEN_DETAILS -> Modifier.clickable(
                        interactionSource = interaction,
                        // No ripple: the border and the enlargement are the mark, and a ripple is
                        // a touch idiom on a device that has no touch screen.
                        indication = null,
                        onClickLabel = stringResource(R.string.dashboard_open_details),
                        onClick = onOpenDetails,
                    )

                    TileAction.OPEN_SETTINGS -> Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClickLabel = stringResource(R.string.dashboard_open_settings),
                        onClick = onOpenSettings,
                    )
                },
            )
            .padding(WidgoraSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(WidgoraSpacing.Small),
    ) {
        Text(
            text = view.title,
            fontSize = WidgoraText.TileTitle.size,
            lineHeight = WidgoraText.TileTitle.lineHeight,
            color = WidgoraColors.OnSurface,
        )

        if (presentation.showsData && overview != null) {
            if (overview.entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.dashboard_no_data_yet),
                    fontSize = WidgoraText.TileValue.size,
                    lineHeight = WidgoraText.TileValue.lineHeight,
                    fontWeight = FontWeight.SemiBold,
                    color = WidgoraColors.OnSurface,
                )
            } else {
                CountsRow(overview.counts)
                AffectedNamesLines(overview)
            }
        }

        // The state is given three ways at once — mark, word and colour — so that the colour is
        // never the only thing carrying it.
        Row(
            horizontalArrangement = Arrangement.spacedBy(WidgoraSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = presentation.mark,
                fontSize = WidgoraText.Label.size,
                lineHeight = WidgoraText.Label.lineHeight,
                fontWeight = FontWeight.Bold,
                color = presentation.color,
            )
            Text(
                text = stringResource(presentation.label),
                fontSize = WidgoraText.Label.size,
                lineHeight = WidgoraText.Label.lineHeight,
                color = presentation.color,
            )
        }

        if (snapshot != null) {
            val context = LocalContext.current
            // The platform's own time format, so a device set to 24 hours shows 24 hours.
            val time = DateFormat.getTimeFormat(context).format(Date(snapshot.updatedAtEpochMs))
            Text(
                text = stringResource(
                    if (presentation.valueIsOutdated) {
                        R.string.dashboard_last_updated_outdated
                    } else {
                        R.string.dashboard_last_updated
                    },
                    time,
                ),
                fontSize = WidgoraText.Caption.size,
                lineHeight = WidgoraText.Caption.lineHeight,
                color = WidgoraColors.OnSurfaceMuted,
            )
        }
    }
}

/**
 * The counts, each as a mark and a number in the colour of its state.
 *
 * A flow row rather than a plain row: five counts at this size do not fit the width of a tile, and
 * both alternatives — running off the edge, or shrinking the type — lose something. Wrapping is
 * safe here because every line carries the line height of its token.
 *
 * Each pair replaces its own semantics with the whole phrase for that number, so that a screen
 * reader says "two down" rather than reading out a geometric character and a digit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CountsRow(counts: MonitorCounts) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(WidgoraSpacing.Small),
    ) {
        for ((state, count) in counts.shown) {
            val presentation = MonitorPresentation.of(state)
            val spoken = pluralStringResource(presentation.countPhrase, count, count)
            Row(
                modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
                horizontalArrangement = Arrangement.spacedBy(WidgoraSpacing.Tiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = presentation.symbol,
                    fontSize = WidgoraText.TileValue.size,
                    lineHeight = WidgoraText.TileValue.lineHeight,
                    color = presentation.color,
                )
                Text(
                    text = count.toString(),
                    fontSize = WidgoraText.TileValue.size,
                    lineHeight = WidgoraText.TileValue.lineHeight,
                    fontWeight = FontWeight.SemiBold,
                    color = presentation.color,
                )
            }
        }
    }
}

/**
 * The names of the items that are not working, at most [MAX_AFFECTED_NAMES] of them.
 *
 * Nothing is drawn when everything is working: a line saying so would occupy the space that
 * matters on the one day it is not true.
 *
 * The overflow count is a line of its own rather than part of the joined text, so that cutting
 * the names at [MAX_AFFECTED_LINES] lines can never swallow the statement that there are more.
 */
@Composable
private fun AffectedNamesLines(overview: MonitorOverview) {
    val affected = overview.affectedNames(MAX_AFFECTED_NAMES, MAX_AFFECTED_NAME_CODE_POINTS)
    if (affected.isEmpty) return

    if (affected.names.isNotEmpty()) {
        Text(
            text = affected.names.joinToString(stringResource(R.string.summary_separator)),
            fontSize = WidgoraText.Label.size,
            lineHeight = WidgoraText.Label.lineHeight,
            color = WidgoraColors.OnSurfaceMuted,
            maxLines = MAX_AFFECTED_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (affected.remaining > 0) {
        Text(
            text = pluralStringResource(
                R.plurals.dashboard_affected_more,
                affected.remaining,
                affected.remaining,
            ),
            fontSize = WidgoraText.Label.size,
            lineHeight = WidgoraText.Label.lineHeight,
            color = WidgoraColors.OnSurfaceMuted,
        )
    }
}

/** Three names is what fits beside the counts without the tile turning into a list. */
private const val MAX_AFFECTED_NAMES = 3

/** Long enough to recognise a name, short enough that three of them stay readable. */
private const val MAX_AFFECTED_NAME_CODE_POINTS = 20

private const val MAX_AFFECTED_LINES = 3

// --- Previews: one per display state, one per notice, one with affected monitors. ---------------

@Preview(widthDp = 520, heightDp = 440)
@Composable
private fun TileOk() = PreviewFrame { PreviewTile(previewView(WidgetDisplayState.Ok)) }

@Preview(widthDp = 520, heightDp = 440)
@Composable
private fun TileWithAffectedMonitors() = PreviewFrame {
    PreviewTile(previewView(WidgetDisplayState.Ok, overview = previewOverviewWithFailures()))
}

@Preview(widthDp = 520, heightDp = 440)
@Composable
private fun TileStale() = PreviewFrame { PreviewTile(previewView(WidgetDisplayState.Stale)) }

@Preview(widthDp = 520, heightDp = 440)
@Composable
private fun TileOffline() = PreviewFrame {
    PreviewTile(previewView(WidgetDisplayState.Offline, overview = previewOverviewWithFailures()))
}

@Preview(widthDp = 520, heightDp = 440)
@Composable
private fun TileError() = PreviewFrame {
    PreviewTile(previewView(WidgetDisplayState.Error(Reason.UNAUTHORIZED)))
}

@Preview(widthDp = 520, heightDp = 440)
@Composable
private fun TileLoading() = PreviewFrame {
    PreviewTile(previewView(WidgetDisplayState.Loading, snapshot = null, overview = null))
}

@Preview(widthDp = 520, heightDp = 440)
@Composable
private fun TileEmpty() = PreviewFrame {
    PreviewTile(
        previewView(WidgetDisplayState.Empty, value = " ", overview = MonitorOverview.Empty),
    )
}

@Preview(widthDp = 520, heightDp = 440)
@Composable
private fun TileDisabled() = PreviewFrame { PreviewTile(previewNotice(InstanceNotice.DISABLED)) }

@Preview(widthDp = 520, heightDp = 440)
@Composable
private fun TileSecretUnusable() = PreviewFrame {
    PreviewTile(previewNotice(InstanceNotice.SECRET_UNUSABLE))
}

@Preview(widthDp = 520, heightDp = 440)
@Composable
private fun TileEntryInvalid() = PreviewFrame {
    PreviewTile(previewNotice(InstanceNotice.ENTRY_INVALID))
}

@Composable
private fun PreviewTile(view: WidgetView) =
    WidgetTile(view = view, onOpenDetails = {}, onOpenSettings = {})

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    WidgoraTheme {
        Column(
            modifier = Modifier
                .background(WidgoraColors.Backdrop)
                .padding(WidgoraSpacing.Large),
        ) {
            content()
        }
    }
}

private fun previewView(
    state: WidgetDisplayState,
    value: String = "▲33 ▼2",
    snapshot: WidgetSnapshot? = previewSnapshot(value),
    overview: MonitorOverview? = previewOverviewAllActive(),
): WidgetView = WidgetView(
    instanceId = PreviewInstance,
    widgetId = KumaSnapshotMapper.summaryWidgetId(PreviewInstance),
    title = "Uptime Kuma",
    snapshot = snapshot,
    state = WidgetViewState.Display(state),
    overview = overview,
)

private fun previewNotice(notice: InstanceNotice): WidgetView = WidgetView(
    instanceId = PreviewInstance,
    widgetId = KumaSnapshotMapper.summaryWidgetId(PreviewInstance),
    title = "Uptime Kuma",
    snapshot = null,
    state = WidgetViewState.Notice(notice),
    overview = null,
)

private fun previewSnapshot(value: String): WidgetSnapshot = WidgetSnapshot.truncating(
    widgetId = KumaSnapshotMapper.summaryWidgetId(PreviewInstance),
    title = "Uptime Kuma",
    primaryValue = value,
    updatedAtEpochMs = 1_700_000_000_000,
    iconToken = KumaSnapshotMapper.ICON_TOKEN,
    accentToken = KumaSnapshotMapper.ACCENT_TOKEN,
)

/** Sample data. It exists only for the previews in this file and reaches no screen. */
private fun previewOverviewAllActive(): MonitorOverview = MonitorOverview(
    (1..35).map { index -> previewEntry(index, MonitorState.ACTIVE) },
)

private fun previewOverviewWithFailures(): MonitorOverview = MonitorOverview(
    listOf(
        previewEntry(1, MonitorState.FAILED),
        previewEntry(2, MonitorState.FAILED),
        previewEntry(3, MonitorState.UNKNOWN),
        previewEntry(4, MonitorState.PENDING),
        previewEntry(5, MonitorState.MAINTENANCE),
    ) + (6..33).map { index -> previewEntry(index, MonitorState.ACTIVE) },
)

private fun previewEntry(index: Int, state: MonitorState): MonitorEntry = MonitorEntry(
    monitorId = index.toString(),
    name = "Sample service " + index,
    state = state,
    responseTimeMs = (index * 7).toLong(),
)

private val PreviewInstance = InstanceId("kuma")
