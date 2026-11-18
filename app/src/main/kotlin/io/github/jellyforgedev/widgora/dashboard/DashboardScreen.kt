package io.github.jellyforgedev.widgora.dashboard

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.InstanceNotice
import io.github.jellyforgedev.widgora.core.data.WidgetView
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import java.text.DateFormat
import java.util.Date

/**
 * The home screen: one tile per configured instance, plus the two actions.
 *
 * It renders what it is given and calls back; it reads nothing and retrieves nothing itself.
 * The presentation is deliberately plain text — the design system is a separate, later step, and
 * anything invented here would have to be undone there.
 */
@Composable
fun DashboardScreen(
    views: List<WidgetView>,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(text = stringResource(R.string.app_name), fontSize = 32.sp)

        if (views.isEmpty()) {
            Text(text = stringResource(R.string.dashboard_no_instance), fontSize = 20.sp)
        } else {
            for (view in views) {
                WidgetTile(view)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onRefresh) {
                Text(stringResource(R.string.dashboard_refresh))
            }
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.home_settings_action))
            }
        }
    }
}

@Composable
private fun WidgetTile(view: WidgetView) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = MaterialTheme.colorScheme.onSurface)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = view.title, fontSize = 24.sp)

        when (val state = view.state) {
            is WidgetViewState.Notice -> Text(text = noticeText(state.notice), fontSize = 20.sp)

            is WidgetViewState.Display -> {
                val snapshot = view.snapshot
                Text(
                    text = snapshot?.primaryValue?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.dashboard_no_data_yet),
                    fontSize = 28.sp,
                )
                Text(text = displayStateText(state.displayState), fontSize = 18.sp)
                if (snapshot != null) {
                    // The timestamp belongs to the data, not to the attempt that failed, which
                    // is what makes an outdated value readable as outdated.
                    Text(
                        text = stringResource(
                            R.string.dashboard_last_updated,
                            formatTime(snapshot.updatedAtEpochMs),
                        ),
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun noticeText(notice: InstanceNotice): String = stringResource(
    when (notice) {
        InstanceNotice.DISABLED -> R.string.dashboard_notice_disabled
        InstanceNotice.SECRET_UNUSABLE -> R.string.dashboard_notice_secret_unusable
        InstanceNotice.ENTRY_INVALID -> R.string.dashboard_notice_entry_invalid
    },
)

@Composable
private fun displayStateText(state: WidgetDisplayState): String = stringResource(
    when (state) {
        WidgetDisplayState.Loading -> R.string.dashboard_state_loading
        WidgetDisplayState.Ok -> R.string.dashboard_state_ok
        WidgetDisplayState.Empty -> R.string.dashboard_state_empty
        WidgetDisplayState.Stale -> R.string.dashboard_state_stale
        WidgetDisplayState.Offline -> R.string.dashboard_state_offline
        is WidgetDisplayState.Error -> when (state.reason) {
            Reason.UNAUTHORIZED -> R.string.dashboard_state_error_unauthorized
            Reason.ENDPOINT_NOT_FOUND -> R.string.dashboard_state_error_endpoint_not_found
            Reason.UNREACHABLE -> R.string.dashboard_state_offline
            Reason.MALFORMED_RESPONSE -> R.string.dashboard_state_error_malformed
            Reason.OVERSIZED -> R.string.dashboard_state_error_oversized
            Reason.UNKNOWN -> R.string.dashboard_state_error_unknown
        }
    },
)

/** The time of day in the device's own format; the date is of no interest at this interval. */
private fun formatTime(epochMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))
