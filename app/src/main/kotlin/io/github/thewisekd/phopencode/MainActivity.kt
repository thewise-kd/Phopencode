package io.github.thewisekd.phopencode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

internal const val EMPTY_SCREEN_TAG = "empty_screen"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EmptyScreen() }
    }
}

@Composable
private fun EmptyScreen() {
    Box(Modifier.fillMaxSize().testTag(EMPTY_SCREEN_TAG))
}
