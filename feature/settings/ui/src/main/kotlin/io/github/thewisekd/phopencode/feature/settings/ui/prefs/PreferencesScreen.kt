package io.github.thewisekd.phopencode.feature.settings.ui.prefs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesIntent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesState

/**
 * Stub from the contract (P10.1); P10.9 draws the screen: language, theme, file size limit,
 * and deleting all data after a confirmation. Draws [state], sends
 * [onIntent]. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun PreferencesScreen(
    route: SettingsRoute.Preferences,
    state: PreferencesState,
    onIntent: (PreferencesIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
