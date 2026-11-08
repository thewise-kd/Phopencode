package io.github.thewisekd.phopencode.feature.settings.ui.prefs

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesIntent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesState

/** Settings as read: English, dark theme, a 10 MiB limit. */
internal val loadedState = PreferencesState(
    loading = false,
    language = AppLanguage.ENGLISH,
    theme = ThemePreference.DARK,
    fileSizeLimitBytes = 10L * PreferencesState.BYTES_PER_MIB,
    fileSizeLimitText = "10",
)

internal fun ComposeContentTestRule.setThemedContent(content: @Composable () -> Unit) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface(content = content)
        }
    }
}

/** Shows the screen with [state] and returns the intents it sends, in order. */
internal fun ComposeContentTestRule.showPreferences(state: PreferencesState): List<PreferencesIntent> {
    val sent = mutableListOf<PreferencesIntent>()
    setThemedContent { PreferencesScreen(SettingsRoute.Preferences, state, onIntent = { sent += it }) }
    return sent
}

/** The smallest size of anything tappable, as `PhopencodeSpacing.touchTarget`. */
internal val TOUCH_TARGET = 48.dp

/** Float rounding slack, the same as Compose's own `assertTouchHeightIsEqualTo`. */
private val TOUCH_SIZE_TOLERANCE = 0.5.dp

/** Asserts the node's touch bounds are at least [minimum] in both directions. */
internal fun SemanticsNodeInteraction.assertTouchSizeIsAtLeast(minimum: Dp = TOUCH_TARGET): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("Failed to check the touch size.")
    val bounds: Rect = node.touchBoundsInRoot
    val (width, height) = with(node.layoutInfo.density) { bounds.width.toDp() to bounds.height.toDp() }
    if (width < minimum - TOUCH_SIZE_TOLERANCE || height < minimum - TOUCH_SIZE_TOLERANCE) {
        throw AssertionError("Actual touch size is $width x $height, expected at least $minimum")
    }
    return this
}
