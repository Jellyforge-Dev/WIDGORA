package io.github.jellyforgedev.widgora.design

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.InstanceNotice
import io.github.jellyforgedev.widgora.core.data.WidgetView
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaSnapshotMapper
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import io.github.jellyforgedev.widgora.model.WidgetSnapshot
import java.util.Date

/** One tile. It shows every display state and every notice, reading what it needs from [view]. */
@Composable
fun WidgetTile(view: WidgetView, modifier: Modifier = Modifier) {
    val presentation = WidgetPresentation.of(view.state)
    val snapshot = view.snapshot

    Column(
        modifier = modifier
            .width(TileWidth)
            .background(WidgoraColors.Surface, WidgoraShapes.Tile)
            .border(WidgoraFocus.RestingBorderWidth, WidgoraColors.Outline, WidgoraShapes.Tile)
            .padding(WidgoraSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(WidgoraSpacing.Small),
    ) {
        Text(
            text = view.title,
            fontSize = WidgoraText.TileTitle,
            color = WidgoraColors.OnSurface,
        )

        if (presentation.showsData && snapshot != null) {
            Text(
                text = snapshot.primaryValue.ifBlank {
                    stringResource(R.string.dashboard_no_data_yet)
                },
                fontSize = WidgoraText.TileValue,
                fontWeight = FontWeight.SemiBold,
                color = WidgoraColors.OnSurface,
            )
        }

        // The state is given three ways at once — mark, word and colour — so that the colour is
        // never the only thing carrying it.
        Row(
            horizontalArrangement = Arrangement.spacedBy(WidgoraSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = presentation.mark,
                fontSize = WidgoraText.Label,
                fontWeight = FontWeight.Bold,
                color = presentation.color,
            )
            Text(
                text = stringResource(presentation.label),
                fontSize = WidgoraText.Label,
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
                fontSize = WidgoraText.Caption,
                color = WidgoraColors.OnSurfaceMuted,
            )
        }
    }
}

private val TileWidth = 420.dp

// --- Previews: one per display state and one per notice. ---------------------------------------

@Preview(widthDp = 500, heightDp = 300)
@Composable
private fun TileOk() = PreviewFrame { WidgetTile(previewView(WidgetDisplayState.Ok)) }

@Preview(widthDp = 500, heightDp = 300)
@Composable
private fun TileStale() = PreviewFrame { WidgetTile(previewView(WidgetDisplayState.Stale)) }

@Preview(widthDp = 500, heightDp = 300)
@Composable
private fun TileOffline() = PreviewFrame { WidgetTile(previewView(WidgetDisplayState.Offline)) }

@Preview(widthDp = 500, heightDp = 300)
@Composable
private fun TileError() = PreviewFrame {
    WidgetTile(previewView(WidgetDisplayState.Error(Reason.UNAUTHORIZED)))
}

@Preview(widthDp = 500, heightDp = 300)
@Composable
private fun TileLoading() = PreviewFrame {
    WidgetTile(previewView(WidgetDisplayState.Loading, snapshot = null))
}

@Preview(widthDp = 500, heightDp = 300)
@Composable
private fun TileEmpty() = PreviewFrame {
    WidgetTile(previewView(WidgetDisplayState.Empty, value = " "))
}

@Preview(widthDp = 500, heightDp = 300)
@Composable
private fun TileDisabled() = PreviewFrame { WidgetTile(previewNotice(InstanceNotice.DISABLED)) }

@Preview(widthDp = 500, heightDp = 300)
@Composable
private fun TileSecretUnusable() = PreviewFrame {
    WidgetTile(previewNotice(InstanceNotice.SECRET_UNUSABLE))
}

@Preview(widthDp = 500, heightDp = 300)
@Composable
private fun TileEntryInvalid() = PreviewFrame {
    WidgetTile(previewNotice(InstanceNotice.ENTRY_INVALID))
}

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
    value: String = "33 up, 2 down",
    snapshot: WidgetSnapshot? = previewSnapshot(value),
): WidgetView = WidgetView(
    instanceId = PreviewInstance,
    widgetId = KumaSnapshotMapper.summaryWidgetId(PreviewInstance),
    title = "Uptime Kuma",
    snapshot = snapshot,
    state = WidgetViewState.Display(state),
)

private fun previewNotice(notice: InstanceNotice): WidgetView = WidgetView(
    instanceId = PreviewInstance,
    widgetId = KumaSnapshotMapper.summaryWidgetId(PreviewInstance),
    title = "Uptime Kuma",
    snapshot = null,
    state = WidgetViewState.Notice(notice),
)

private fun previewSnapshot(value: String): WidgetSnapshot = WidgetSnapshot.truncating(
    widgetId = KumaSnapshotMapper.summaryWidgetId(PreviewInstance),
    title = "Uptime Kuma",
    primaryValue = value,
    updatedAtEpochMs = 1_700_000_000_000,
    iconToken = KumaSnapshotMapper.ICON_TOKEN,
    accentToken = KumaSnapshotMapper.ACCENT_TOKEN,
)

private val PreviewInstance = InstanceId("kuma")
