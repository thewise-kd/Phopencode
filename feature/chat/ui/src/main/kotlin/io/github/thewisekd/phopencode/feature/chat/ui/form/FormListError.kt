package io.github.thewisekd.phopencode.feature.chat.ui.form

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormIntent

/**
 * Stub from the contract (P7.1); P7.3 draws says that the subagents' forms could not be listed,
 * with a retry that sends `FormIntent.Retry`. The chat draws it only while `FormsState.error` is
 * set.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun FormListError(
    error: ChatFailure,
    onIntent: (FormIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
