package io.github.thewisekd.phopencode.feature.preview.ui.page

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NavigationDecision
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState

/**
 * Stub from the contract (P13.5); P13.13 draws the screen: an isolated WebView that loads
 * [PageState.load] from the bridge on the phone's loopback, asks [decide] before following any
 * address, reports loads, errors and console messages through [onIntent], and clears its data and
 * is destroyed when [PageState.status] is closed or the screen leaves. P13.14 adds the console
 * panel and P13.15 the viewport toggle. The root keeps the `placeholder:<route>` tag the app's
 * navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun PageScreen(
    route: PreviewRoute.Page,
    state: PageState,
    onIntent: (PageIntent) -> Unit,
    decide: (String) -> NavigationDecision,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
