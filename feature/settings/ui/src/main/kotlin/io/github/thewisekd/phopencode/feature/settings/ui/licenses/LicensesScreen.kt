package io.github.thewisekd.phopencode.feature.settings.ui.licenses

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState

/**
 * Stub from the contract (P10.1); P12.3 draws the screen: every dependency's license notice, the
 * LGPL notice and the TextMate grammar licenses. The notices are fixed text shipped with the app, so
 * the screen has no presenter; [onClose] goes back. The root keeps the `placeholder:<route>` tag the
 * app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun LicensesScreen(
    route: SettingsRoute.Licenses,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
