package io.github.thewisekd.phopencode.feature.chat.ui.permission

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionIntent

/**
 * Stub from the contract (P7.1); P7.5 draws says that the subagents' permission requests could not
 * be listed, with a retry that sends `PermissionIntent.Retry`. The chat draws it only while
 * `PermissionsState.error` is set.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun PermissionListError(
    error: ChatFailure,
    onIntent: (PermissionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
