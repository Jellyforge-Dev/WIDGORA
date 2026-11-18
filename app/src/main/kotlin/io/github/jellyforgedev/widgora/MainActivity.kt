package io.github.jellyforgedev.widgora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import io.github.jellyforgedev.widgora.settings.ConnectionTester
import io.github.jellyforgedev.widgora.settings.InstanceStore
import io.github.jellyforgedev.widgora.settings.SettingsScreen
import io.github.jellyforgedev.widgora.settings.SettingsStateHolder

// tv-material3 applies its light colour scheme unless one is passed in, which is wrong for a
// television. This is the library's own dark scheme, held in one place so it is not rebuilt on
// every recomposition. No project colour is defined here: the design system is a separate,
// later step and is not anticipated by this screen.
private val PlaceholderColorScheme = darkColorScheme()

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgora = application as WidgoraApplication
        setContent {
            MaterialTheme(colorScheme = PlaceholderColorScheme) {
                WidgoraApp(
                    instanceStore = widgora.instanceStore,
                    connectionTester = widgora.connectionTester,
                )
            }
        }
    }
}

/**
 * The whole application at this stage: a placeholder screen and the settings screen.
 *
 * Navigation is one flag rather than a navigation library. There are two destinations, and the
 * back key is the only way out of the second one, which is what a television remote offers.
 */
@Composable
private fun WidgoraApp(instanceStore: InstanceStore, connectionTester: ConnectionTester) {
    var showSettings by rememberSaveable { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
        if (showSettings) {
            BackHandler { showSettings = false }
            val holder = remember(instanceStore, connectionTester) {
                SettingsStateHolder(instanceStore, connectionTester)
            }
            SettingsScreen(holder)
        } else {
            PlaceholderScreen(onOpenSettings = { showSettings = true })
        }
    }
}

@Composable
private fun PlaceholderScreen(onOpenSettings: () -> Unit) {
    // The surface passes its minimum constraints on to its content, so a single child fills the
    // screen and draws in its top left corner. Centring is therefore done by a box of its own
    // rather than by aligning the child.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(text = stringResource(R.string.placeholder_message))
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.home_settings_action))
            }
        }
    }
}
