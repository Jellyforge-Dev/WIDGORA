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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme
import io.github.jellyforgedev.widgora.core.data.SnapshotRepository
import io.github.jellyforgedev.widgora.dashboard.DashboardScreen
import io.github.jellyforgedev.widgora.dashboard.DashboardTiming
import io.github.jellyforgedev.widgora.settings.ConnectionTester
import io.github.jellyforgedev.widgora.settings.InstanceStore
import io.github.jellyforgedev.widgora.settings.SettingsScreen
import io.github.jellyforgedev.widgora.settings.SettingsStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// tv-material3 applies its light colour scheme unless one is passed in, which is wrong for a
// television. This is the library's own dark scheme, held in one place so it is not rebuilt on
// every recomposition. No project colour is defined here: the design system is a separate,
// later step.
private val WidgoraColorScheme = darkColorScheme()

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
            MaterialTheme(colorScheme = WidgoraColorScheme) {
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
 * The whole application at this stage: the dashboard and the settings screen.
 *
 * Navigation is one flag rather than a navigation library. There are two destinations, and the
 * back key is the only way out of the second one, which is what a television remote offers.
 */
@Composable
private fun WidgoraApp(
    instanceStore: InstanceStore,
    connectionTester: ConnectionTester,
    snapshots: SnapshotRepository,
    foreground: Boolean,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val views by snapshots.views.collectAsState()
    val scope = rememberCoroutineScope()

    /**
     * Retrieves while the dashboard is actually in front of the user, and stops otherwise.
     *
     * The effect is keyed on both conditions, so leaving the foreground or opening the settings
     * cancels it and coming back starts it again — which is also what makes the dashboard
     * refresh after returning from the settings.
     */
    LaunchedEffect(foreground, showSettings) {
        if (!foreground || showSettings) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) { snapshots.refresh() }
            delay(DashboardTiming.REFRESH_INTERVAL_MS)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
        if (showSettings) {
            BackHandler { showSettings = false }
            val holder = remember(instanceStore, connectionTester) {
                SettingsStateHolder(instanceStore, connectionTester)
            }
            SettingsScreen(holder)
        } else {
            DashboardScreen(
                views = views,
                onRefresh = {
                    scope.launch { withContext(Dispatchers.IO) { snapshots.refresh() } }
                },
                onOpenSettings = { showSettings = true },
            )
        }
    }
}
