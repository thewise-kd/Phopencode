package io.github.thewisekd.phopencode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.navigation.AppNavHost

/**
 * The only activity. It follows the system language: it sets no locale of its own, so
 * the per-app language Android keeps for `locales_config.xml` applies as well.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PhopencodeTheme {
                AppNavHost(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }
        }
    }
}
