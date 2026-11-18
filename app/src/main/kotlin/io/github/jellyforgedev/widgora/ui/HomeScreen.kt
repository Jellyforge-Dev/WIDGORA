package io.github.jellyforgedev.widgora.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import io.github.jellyforgedev.widgora.design.WidgoraOverscan
import io.github.jellyforgedev.widgora.design.WidgoraSpacing
import io.github.jellyforgedev.widgora.design.WidgoraText

/**
 * The home screen: a horizontal row of tiles and the two actions.
 *
 * A lazy row even though there is one tile today: the next integrations add tiles to this very
 * row, and a row that only starts scrolling once there are many would have to be rebuilt then.
 *
 * The padding is the overscan margin from the design tokens, so nothing lands in the part of the
 * picture a television may cut off.
 */
@Composable
fun HomeScreen(
    views: List<WidgetView>,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
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
            fontSize = WidgoraText.ScreenTitle,
            color = WidgoraColors.OnSurface,
        )

        if (views.isEmpty()) {
            Text(
                text = stringResource(R.string.dashboard_no_instance),
                fontSize = WidgoraText.Label,
                color = WidgoraColors.OnSurfaceMuted,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium)) {
                items(views) { view -> WidgetTile(view) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(WidgoraSpacing.Medium)) {
            Button(onClick = onRefresh) {
                Text(
                    text = stringResource(R.string.dashboard_refresh),
                    fontSize = WidgoraText.Label,
                )
            }
            Button(onClick = onOpenSettings) {
                Text(
                    text = stringResource(R.string.home_settings_action),
                    fontSize = WidgoraText.Label,
                )
            }
        }
    }
}
