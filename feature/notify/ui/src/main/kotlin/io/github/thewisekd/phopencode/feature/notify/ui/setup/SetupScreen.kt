package io.github.thewisekd.phopencode.feature.notify.ui.setup

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupState

/**
 * Stub from the contract (P11.7); P11.10 draws the screen: why the ntfy app is needed, the
 * `ntfy://` subscription link (opened with the system; the topic itself is never shown in full or
 * copied to a log), the ntfy server field, the service's status with install and remove, and the
 * test notification. Draws [state], sends [onIntent]. The root keeps the `placeholder:<route>` tag
 * the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SetupScreen(
    route: NotifyRoute.Setup,
    state: SetupState,
    onIntent: (SetupIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
