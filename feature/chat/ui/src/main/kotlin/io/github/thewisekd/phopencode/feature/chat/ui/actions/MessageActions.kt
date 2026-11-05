package io.github.thewisekd.phopencode.feature.chat.ui.actions

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState

/**
 * Stub from the contract (P7.1); P7.15 draws the actions of the prompt [messageId], drawn by the
 * timeline after a long press on it: "this turn's changes" (`ActionsIntent.ShowTurnChanges`) and
 * "fork from here" (`ActionsIntent.Request` with `SessionAction.Fork`). Calls [onDismiss] once it
 * is done or dismissed.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun MessageActions(
    messageId: MessageId,
    state: ActionsState,
    onIntent: (ActionsIntent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
