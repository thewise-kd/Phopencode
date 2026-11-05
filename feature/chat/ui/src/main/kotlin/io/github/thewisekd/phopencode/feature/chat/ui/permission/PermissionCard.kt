package io.github.thewisekd.phopencode.feature.chat.ui.permission

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionIntent

/**
 * Stub from the contract (P7.1); P7.5 draws draws one pending permission request: its action and
 * resources spelled out, who asks, and once, always (behind its own confirmation) and reject with a
 * message. Draws [card], sends [onIntent] for it.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun PermissionCard(
    card: PermissionCardState,
    onIntent: (PermissionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
