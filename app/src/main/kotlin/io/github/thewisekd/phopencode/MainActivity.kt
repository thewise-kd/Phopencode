package io.github.thewisekd.phopencode

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.navigation.AppNavHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.android.ext.android.inject

/**
 * The only activity. It follows the system language: it sets no locale of its own, so
 * the per-app language Android keeps for `locales_config.xml` applies as well.
 *
 * The theme follows the stored [ThemePreference] (see [rememberDarkTheme]); a change recomposes
 * the content in place, without recreating the activity. The system bar icons follow the same
 * theme, so a forced dark theme on a light phone does not get dark icons on a dark bar.
 */
class MainActivity : ComponentActivity() {
    private val settings: SettingsRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val darkTheme = rememberDarkTheme(settings)
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme },
                )
                onDispose {}
            }
            PhopencodeTheme(darkTheme = darkTheme) {
                AppNavHost(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }
        }
    }

    private companion object {
        /** The navigation bar scrims `enableEdgeToEdge` uses by default (androidx.activity 1.13). */
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}

/** Whether this preference means a dark theme on a phone whose own dark mode is [systemDark]. */
internal fun ThemePreference.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemePreference.SYSTEM -> systemDark
    ThemePreference.LIGHT -> false
    ThemePreference.DARK -> true
}

/**
 * Whether the app is dark: the stored [ThemePreference] applied to the phone's dark mode. Until
 * the first settings value arrives it follows the phone, so a dark phone does not flash a light
 * theme while the store loads. If reading the settings fails, the error is dropped and the last
 * value received stays in force, so the theme does not flip suddenly; if none was received yet,
 * it keeps following the phone.
 */
@Composable
internal fun rememberDarkTheme(settings: SettingsRepository): Boolean {
    val preferences: Flow<ThemePreference> = remember(settings) {
        settings.observe()
            .map { it.theme }
            .distinctUntilChanged()
            // Ends the flow without emitting: collectAsState keeps the last value it had.
            .catch { }
    }
    val preference by preferences.collectAsState(initial = ThemePreference.SYSTEM)
    return preference.isDark(systemDark = isSystemInDarkTheme())
}
