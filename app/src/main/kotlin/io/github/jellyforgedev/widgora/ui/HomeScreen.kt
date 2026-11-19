package io.github.jellyforgedev.widgora.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.core.data.WidgetView
import io.github.jellyforgedev.widgora.design.WidgetTile
import io.github.jellyforgedev.widgora.design.WidgoraColors
import io.github.jellyforgedev.widgora.design.WidgoraFocus
import io.github.jellyforgedev.widgora.design.WidgoraOverscan
import io.github.jellyforgedev.widgora.design.WidgoraSpacing
import io.github.jellyforgedev.widgora.design.WidgoraText
import io.github.jellyforgedev.widgora.model.InstanceId

/**
 * The home screen: a horizontal row of tiles and the two actions.
 *
 * A lazy row even though there is one tile today: the next integrations add tiles to this very
 * row, and a row that only starts scrolling once there are many would have to be rebuilt then.
 *
 * The padding is the overscan margin from the design tokens, so nothing lands in the part of the
 * picture a television may cut off.
 *
 * @param configurationUnreadable the stored configuration exists but could not be read. It is a
 *   parameter of its own rather than an empty list, because "nothing is set up" and "what is set
 *   up cannot be read" need different words and a different action from the user.
 */
@Composable
fun HomeScreen(
    views: List<WidgetView>,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetails: (InstanceId) -> Unit,
    configurationUnreadable: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                horizontal = WidgoraOverscan.Horizontal,
                vertical = WidgoraOverscan.Vertical,
            ),
        verticalArrangement = Arrangement.spacedBy(WidgoraSpacing.Large),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            fontSize = WidgoraText.ScreenTitle.size,
            lineHeight = WidgoraText.ScreenTitle.lineHeight,
            color = WidgoraColors.OnSurface,
        )

        when {
            configurationUnreadable -> Text(
                text = stringResource(R.string.dashboard_config_unreadable),
                fontSize = WidgoraText.Label.size,
                lineHeight = WidgoraText.Label.lineHeight,
                color = WidgoraColors.StateError,
            )

            views.isEmpty() -> Text(
                text = stringResource(R.string.dashboard_no_instance),
                fontSize = WidgoraText.Label.size,
                lineHeight = WidgoraText.Label.lineHeight,
                color = WidgoraColors.OnSurfaceMuted,
            )

            else -> LazyRow(
                horizontalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium),
                // A lazy list clips its own bounds, so a focused tile's enlargement would be cut
                // off at the first and last position — the two places the focus is most likely to
                // be. The padding is inside the scrolling area, so it also applies to the ends.
                contentPadding = PaddingValues(WidgoraFocus.ScaleHeadroom),
            ) {
                items(views, key = { view -> view.instanceId.value }) { view ->
                    WidgetTile(
                        view = view,
                        onOpenDetails = { onOpenDetails(view.instanceId) },
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium)) {
            Button(onClick = onRefresh) {
                Text(
                    text = stringResource(R.string.dashboard_refresh),
                    fontSize = WidgoraText.Label.size,
                    lineHeight = WidgoraText.Label.lineHeight,
                )
            }
            Button(onClick = onOpenSettings) {
                Text(
                    text = stringResource(R.string.home_settings_action),
                    fontSize = WidgoraText.Label.size,
                    lineHeight = WidgoraText.Label.lineHeight,
                )
            }
        }
    }
}
