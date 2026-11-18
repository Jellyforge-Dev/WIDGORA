package io.github.jellyforgedev.widgora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

// tv-material3 applies its light colour scheme unless one is passed in, which is wrong for a
// television. This is the library's own dark scheme, held in one place so it is not rebuilt on
// every recomposition. No project colour is defined here: the design system is a separate,
// later step and is not anticipated by this fix.
private val PlaceholderColorScheme = darkColorScheme()

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = PlaceholderColorScheme) {
                PlaceholderScreen()
            }
        }
    }
}

@Composable
private fun PlaceholderScreen() {
    // A Surface rather than a Box with a background colour: it provides the content colour that
    // belongs to its container colour, so text inside it is legible without being coloured by
    // hand. Setting a text colour individually would fix this one screen and leave every later
    // screen to repeat the mistake.
    Surface(
        modifier = Modifier.fillMaxSize(),
        // A container that covers the whole screen must not have rounded corners.
        shape = RectangleShape,
    ) {
        // The surface passes its minimum constraints on to its content, so a single child fills
        // the screen and draws in its top left corner. Centring is therefore done by a box of
        // its own rather than by aligning the child.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = stringResource(R.string.placeholder_message))
        }
    }
}
