package io.github.jellyforgedev.widgora.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.tv.material3.Text
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.R as CoreR
import io.github.jellyforgedev.widgora.core.data.MonitorEntry
import io.github.jellyforgedev.widgora.core.data.MonitorNote
import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.core.data.MonitorState
import io.github.jellyforgedev.widgora.core.data.WidgetView
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaSnapshotMapper
import io.github.jellyforgedev.widgora.design.MonitorNoteText
import io.github.jellyforgedev.widgora.design.MonitorPresentation
import io.github.jellyforgedev.widgora.design.WidgetPresentation
import io.github.jellyforgedev.widgora.design.WidgoraColors
import io.github.jellyforgedev.widgora.design.WidgoraFocus
import io.github.jellyforgedev.widgora.design.WidgoraOverscan
import io.github.jellyforgedev.widgora.design.WidgoraShapes
import io.github.jellyforgedev.widgora.design.WidgoraSpacing
import io.github.jellyforgedev.widgora.design.WidgoraText
import io.github.jellyforgedev.widgora.design.WidgoraTheme
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import io.github.jellyforgedev.widgora.model.WidgetSnapshot
import java.util.Date

/**
 * Every monitored item of one instance, the ones needing attention first.
 *
 * It renders the same state and the same timestamp as the tile, from the same [WidgetView], so the
 * two screens cannot disagree about whether the data is current. When a retrieval failed, the list
 * shown is the one from the last success and the state line and timestamp say so — an empty list
 * would be a claim that the installation has no monitors.
 *
 * The rows are focusable without being actionable. There is nothing to open behind a row, but a
 * directional pad can only scroll a list by moving focus through it, so focusability is what makes
 * the list reachable with a remote at all. Each row therefore carries a visible focus mark, so
 * that the thing the viewer is moving is never invisible.
 */
@Composable
fun MonitorDetailScreen(view: WidgetView, modifier: Modifier = Modifier) {
    val presentation = WidgetPresentation.of(view.state)
    val entries = view.overview?.inDetailOrder.orEmpty()
    val firstRow = remember { FocusRequester() }
    val hasEntries = entries.isNotEmpty()

    LaunchedEffect(hasEntries) {
        if (!hasEntries) return@LaunchedEffect
        // A lazy list composes its first row while measuring, which happens after this effect is
        // dispatched. Waiting one frame is what makes the requester initialised; the guard covers
        // the case where it still is not, because failing to place the focus must not take the
        // screen down.
        withFrameNanos { }
        runCatching { firstRow.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                horizontal = WidgoraOverscan.Horizontal,
                vertical = WidgoraOverscan.Vertical,
            ),
        verticalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium),
    ) {
        Text(
            text = view.title,
            fontSize = WidgoraText.ScreenTitle.size,
            lineHeight = WidgoraText.ScreenTitle.lineHeight,
            color = WidgoraColors.OnSurface,
        )
        Text(
            text = stringResource(R.string.detail_back_hint),
            fontSize = WidgoraText.Caption.size,
            lineHeight = WidgoraText.Caption.lineHeight,
            color = WidgoraColors.OnSurfaceMuted,
        )

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

        view.snapshot?.let { snapshot ->
            val context = LocalContext.current
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

        if (!hasEntries) {
            Text(
                text = stringResource(R.string.detail_no_monitors),
                fontSize = WidgoraText.Label.size,
                lineHeight = WidgoraText.Label.lineHeight,
                color = WidgoraColors.OnSurfaceMuted,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(WidgoraSpacing.Small),
                // Room for the focus border of the first and last row. A lazy list clips its own
                // bounds, and without this the thick border of the row the focus starts on is
                // shaved off against the top edge.
                contentPadding = PaddingValues(vertical = WidgoraFocus.ScaleHeadroom),
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.monitorId }) { index, entry ->
                    MonitorRow(
                        entry = entry,
                        modifier = if (index == 0) {
                            Modifier.focusRequester(firstRow)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}

/**
 * One item: mark, name, state in a word and, where the service measured one, a response time.
 *
 * A note, where the item carries one, goes on a line of its own below. It is the only place in the
 * application that explains a single item, and it exists because "unknown" beside a monitor the
 * user can see working elsewhere reads as a defect in WIDGORA rather than as a statement about the
 * answer the service gave. The tile deliberately says nothing about it: a count cannot carry a
 * reason, and a tile that tried would be explaining something the viewer cannot yet see.
 *
 * The mark is removed from the accessibility tree. It repeats what the word beside it already
 * says, and a geometric character read aloud is noise.
 *
 * The focus mark here is the border alone, in the same [WidgoraColors.Focus] as everywhere else,
 * without the enlargement a tile gets. A row already spans the full width, so enlarging it would
 * push its edges into the overscan margin — the part of the picture a television may cut off — and
 * no amount of content padding can buy room that is not there. What makes the focused row obvious
 * instead is that it is the only bright border on the screen.
 */
@Composable
private fun MonitorRow(entry: MonitorEntry, modifier: Modifier = Modifier) {
    val presentation = MonitorPresentation.of(entry.state)
    var focused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(WidgoraColors.Surface, WidgoraShapes.Field)
            .border(
                width = WidgoraFocus.borderWidth(focused),
                color = WidgoraFocus.borderColor(focused),
                shape = WidgoraShapes.Field,
            )
            .onFocusChanged { focusState -> focused = focusState.isFocused }
            .focusable()
            .padding(WidgoraSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(WidgoraSpacing.Tiny),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = presentation.symbol,
                fontSize = WidgoraText.Label.size,
                lineHeight = WidgoraText.Label.lineHeight,
                fontWeight = FontWeight.Bold,
                color = presentation.color,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Text(
                text = entry.name,
                fontSize = WidgoraText.Label.size,
                lineHeight = WidgoraText.Label.lineHeight,
                color = WidgoraColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(presentation.word),
                fontSize = WidgoraText.Label.size,
                lineHeight = WidgoraText.Label.lineHeight,
                color = presentation.color,
            )
            entry.responseTimeMs?.let { responseTimeMs ->
                Text(
                    text = stringResource(R.string.detail_response_time, responseTimeMs),
                    fontSize = WidgoraText.Caption.size,
                    lineHeight = WidgoraText.Caption.lineHeight,
                    color = WidgoraColors.OnSurfaceMuted,
                )
            }
        }

        entry.note?.let { note ->
            Text(
                // The colour of the state it explains, not the muted one a caption usually takes:
                // this line is the reason the mark above it looks the way it does, and a reader
                // who has noticed the mark should find the explanation without hunting for it.
                text = noteText(note),
                fontSize = WidgoraText.Caption.size,
                lineHeight = WidgoraText.Caption.lineHeight,
                color = presentation.color,
            )
        }
    }
}

/**
 * The two halves of a note, in one line.
 *
 * The neutral half always exists; the advice is there only when the integration that reported the
 * item had something to add. They are joined by a space because both are whole sentences — nothing
 * is being punctuated across a language boundary here.
 */
@Composable
private fun noteText(note: MonitorNote): String {
    val reason = stringResource(MonitorNoteText.reasonOf(note.kind))
    val advice = note.sourceAdvice?.let { resource -> stringResource(resource) }
    return if (advice == null) reason else reason + " " + advice
}

// --- Previews ----------------------------------------------------------------------------------

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun DetailWithFailures() = PreviewFrame {
    MonitorDetailScreen(previewView(WidgetDisplayState.Ok, previewOverview()))
}

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun DetailOfflineWithLastKnownList() = PreviewFrame {
    MonitorDetailScreen(previewView(WidgetDisplayState.Offline, previewOverview()))
}

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun DetailWithoutData() = PreviewFrame {
    MonitorDetailScreen(previewView(WidgetDisplayState.Loading, null))
}

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    WidgoraTheme {
        Column(modifier = Modifier.background(WidgoraColors.Backdrop)) { content() }
    }
}

private fun previewView(
    state: WidgetDisplayState,
    overview: MonitorOverview?,
): WidgetView = WidgetView(
    instanceId = PreviewInstance,
    widgetId = KumaSnapshotMapper.summaryWidgetId(PreviewInstance),
    title = "Uptime Kuma",
    snapshot = WidgetSnapshot.truncating(
        widgetId = KumaSnapshotMapper.summaryWidgetId(PreviewInstance),
        title = "Uptime Kuma",
        primaryValue = "▲4 ▼2",
        updatedAtEpochMs = 1_700_000_000_000,
        iconToken = KumaSnapshotMapper.ICON_TOKEN,
        accentToken = KumaSnapshotMapper.ACCENT_TOKEN,
    ),
    state = WidgetViewState.Display(state),
    overview = overview,
)

/** Sample data. It exists only for the previews in this file and reaches no screen. */
private fun previewOverview(): MonitorOverview = MonitorOverview(
    listOf(
        previewEntry(1, MonitorState.ACTIVE, 24),
        previewEntry(2, MonitorState.FAILED, null),
        previewEntry(3, MonitorState.ACTIVE, 180),
        previewEntry(4, MonitorState.PENDING, null),
        previewEntry(5, MonitorState.FAILED, null),
        previewEntry(6, MonitorState.MAINTENANCE, null),
        previewEntry(7, MonitorState.UNKNOWN, 91),
        previewEntry(
            index = 8,
            state = MonitorState.UNKNOWN,
            responseTimeMs = null,
            // The explained case beside the unexplained one above it, which is the comparison the
            // preview exists for: both read "unknown", and only one of them has anything to say.
            note = MonitorNote(
                kind = MonitorNote.Kind.CONTRADICTORY_SOURCE_DATA,
                sourceAdvice = CoreR.string.kuma_note_contradicting_rows,
            ),
        ),
        previewEntry(9, MonitorState.ACTIVE, 12),
    ),
)

private fun previewEntry(
    index: Int,
    state: MonitorState,
    responseTimeMs: Long?,
    note: MonitorNote? = null,
): MonitorEntry = MonitorEntry(
    monitorId = index.toString(),
    name = "Sample service " + index,
    state = state,
    responseTimeMs = responseTimeMs,
    note = note,
)

private val PreviewInstance = InstanceId("kuma")
