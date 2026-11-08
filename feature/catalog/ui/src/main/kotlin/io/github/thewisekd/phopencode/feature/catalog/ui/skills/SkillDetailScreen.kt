package io.github.thewisekd.phopencode.feature.catalog.ui.skills

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailState

/**
 * Stub from the contract (P10.1); P10.3 draws the screen: the skill's name, description and
 * source path, its text as Markdown, and starting a new session with it. Draws [state], sends
 * [onIntent]. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SkillDetailScreen(
    route: CatalogRoute.SkillDetail,
    state: SkillDetailState,
    onIntent: (SkillDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
