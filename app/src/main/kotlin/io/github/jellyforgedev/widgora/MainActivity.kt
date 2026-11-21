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
import androidx.compose.ui.platform.LocalContext
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
import io.github.jellyforgedev.widgora.widget.WidgetLaunch
import io.github.jellyforgedev.widgora.widget.WidgetRefreshScheduler
import io.github.jellyforgedev.widgora.widget.WidgetUpdater
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

        /**
         * Whether this launch came from one of WIDGORA's own widgets.
         *
         * The identifier is validated rather than believed: an intent may carry any number, and
         * one that names no widget of this application is simply not a widget launch. Nothing is
         * reported about a bad value, because nothing went wrong — the activity opens as it
         * otherwise would.
         */
        val openedFromWidget = WidgetLaunch.validAppWidgetIdFrom(this, intent) != null

        /*
         * Brings the periodic widget refresh in line with the widgets that are actually placed.
         *
         * Here rather than in the application class, which is the other candidate and the wrong
         * one: a widget broadcast starts the process too, and putting this in Application.onCreate
         * would open the work database on every such start in order to re-assert something already
         * stored. This is the repair for the cases where queue and reality drifted apart — an
         * update installed over a version that scheduled nothing, a cancellation that never
         * arrived, work lost with the application's data — and opening the application is exactly
         * when a repair costs nothing anybody notices.
         */
        WidgetRefreshScheduler.applyToPlacedWidgets(this)

        setContent {
            WidgoraTheme {
                WidgoraApp(
                    instanceStore = widgora.instanceStore,
                    connectionTester = widgora.connectionTester,
                    snapshots = widgora.snapshots,
                    foreground = foreground.collectAsState().value,
                    openedFromWidget = openedFromWidget,
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
    openedFromWidget: Boolean,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var detailInstanceId by rememberSaveable { mutableStateOf<String?>(null) }
    val views by snapshots.views.collectAsState()
    val configurationUnreadable by snapshots.configurationUnreadable.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    /**
     * A widget shows the dashboard's summary, so pressing one arrives at the dashboard.
     *
     * It matters only where saved state survives the launch — the activity being restored rather
     * than created fresh — because then the application could otherwise reopen on the settings
     * screen, which is not what the widget was showing.
     */
    LaunchedEffect(openedFromWidget) {
        if (openedFromWidget) {
            showSettings = false
            detailInstanceId = null
        }
    }

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
            // Widgets are pushed after every retrieval, including one that failed. A widget left
            // showing the last good value while the dashboard beside it says the service is down
            // would be the one outcome worth avoiding, and the push itself costs no network.
            WidgetUpdater.pushFromMemory(context)
            delay(DashboardTiming.REFRESH_INTERVAL_MS)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
        when {
            showSettings -> {
                val holder = remember(instanceStore, connectionTester) {
                    SettingsStateHolder(instanceStore, connectionTester)
                }
                // The screen is told it is being left rather than left to notice. A revealed API
                // key must not still be revealed the next time the settings are opened, and
                // whether this holder survives at all is decided here — in a line that can
                // change — and not on that screen.
                BackHandler {
                    holder.onLeaveScreen()
                    showSettings = false
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
                    scope.launch {
                        withContext(Dispatchers.IO) { snapshots.refresh() }
                        WidgetUpdater.pushFromMemory(context)
                    }
                },
                onOpenSettings = { showSettings = true },
                onOpenDetails = { instanceId -> detailInstanceId = instanceId.value },
                configurationUnreadable = configurationUnreadable,
            )
        }
    }
}
