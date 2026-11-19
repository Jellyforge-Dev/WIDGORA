package io.github.jellyforgedev.widgora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.tv.material3.Surface
import io.github.jellyforgedev.widgora.core.data.SnapshotRepository
import io.github.jellyforgedev.widgora.dashboard.DashboardTiming
import io.github.jellyforgedev.widgora.design.TileAction
import io.github.jellyforgedev.widgora.design.WidgoraTheme
import io.github.jellyforgedev.widgora.settings.ConnectionTester
import io.github.jellyforgedev.widgora.settings.InstanceStore
import io.github.jellyforgedev.widgora.settings.SettingsScreen
import io.github.jellyforgedev.widgora.settings.SettingsStateHolder
import io.github.jellyforgedev.widgora.ui.HomeScreen
import io.github.jellyforgedev.widgora.ui.MonitorDetailScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /**
     * Whether this activity is in the foreground.
     *
     * Read from the activity's own callbacks rather than through a lifecycle library: the
     * dependency tree carries lifecycle-runtime-compose only as a constraint, not as an actual
     * dependency, and one boolean is not worth adding one for.
     */
    private val foreground = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgora = application as WidgoraApplication
        setContent {
            WidgoraTheme {
                WidgoraApp(
                    instanceStore = widgora.instanceStore,
                    connectionTester = widgora.connectionTester,
                    snapshots = widgora.snapshots,
                    foreground = foreground.collectAsState().value,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground.value = true
    }

    override fun onPause() {
        foreground.value = false
        super.onPause()
    }
}

/**
 * The whole application at this stage: the dashboard, one detail screen and the settings.
 *
 * Navigation is two pieces of saved state rather than a navigation library. There are three
 * destinations, the back key is the only way out of the latter two, and the detail screen is
 * addressed by the identifier of its instance rather than by its data — so that it keeps rendering
 * whatever the next retrieval produced instead of a copy taken when it was opened.
 */
@Composable
private fun WidgoraApp(
    instanceStore: InstanceStore,
    connectionTester: ConnectionTester,
    snapshots: SnapshotRepository,
    foreground: Boolean,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var detailInstanceId by rememberSaveable { mutableStateOf<String?>(null) }
    val views by snapshots.views.collectAsState()
    val configurationUnreadable by snapshots.configurationUnreadable.collectAsState()
    val scope = rememberCoroutineScope()

    /**
     * The instance whose detail screen is open, if it still has a list to show.
     *
     * The second condition matters as much as the first. A tile only opens this screen while it
     * has items, but a later retrieval can turn a notice on — the user switching the instance off,
     * say — and the screen already open would then be left with nothing to list. Deriving it here
     * rather than keeping the decision made at opening time means the screen closes itself instead
     * of becoming the dead end the tile was changed to avoid.
     */
    val detailView = detailInstanceId
        ?.let { id -> views.firstOrNull { view -> view.instanceId.value == id } }
        ?.takeIf { view -> TileAction.of(view) == TileAction.OPEN_DETAILS }

    /**
     * Leaves the detail screen if its instance is gone, or has nothing left to list.
     *
     * The guard on an empty list matters: before the first retrieval there are no views at all,
     * and that is not the same thing as the instance having been deleted.
     */
    LaunchedEffect(detailInstanceId, detailView, views.isEmpty()) {
        if (detailInstanceId != null && detailView == null && views.isNotEmpty()) {
            detailInstanceId = null
        }
    }

    /**
     * Retrieves while the data is actually in front of the user, and stops otherwise.
     *
     * The effect is keyed on both conditions, so leaving the foreground or opening the settings
     * cancels it and coming back starts it again — which is also what makes the dashboard refresh
     * after returning from the settings. The detail screen is deliberately not a reason to stop:
     * it shows the same data and should keep it current.
     */
    LaunchedEffect(foreground, showSettings) {
        if (!foreground || showSettings) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) { snapshots.refresh() }
            delay(DashboardTiming.REFRESH_INTERVAL_MS)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
        when {
            showSettings -> {
                BackHandler { showSettings = false }
                val holder = remember(instanceStore, connectionTester) {
                    SettingsStateHolder(instanceStore, connectionTester)
                }
                SettingsScreen(holder)
            }

            detailView != null -> {
                BackHandler { detailInstanceId = null }
                MonitorDetailScreen(detailView)
            }

            else -> HomeScreen(
                views = views,
                onRefresh = {
                    scope.launch { withContext(Dispatchers.IO) { snapshots.refresh() } }
                },
                onOpenSettings = { showSettings = true },
                onOpenDetails = { instanceId -> detailInstanceId = instanceId.value },
                configurationUnreadable = configurationUnreadable,
            )
        }
    }
}
