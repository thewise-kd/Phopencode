package io.github.thewisekd.phopencode.feature.chat.ui.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SessionAction
import io.github.thewisekd.phopencode.feature.chat.ui.R

/**
 * The actions of the prompt [messageId] (P7.15), drawn by the timeline under the prompt after a
 * long press on it, in a row that wraps on a narrow screen:
 *
 * - **This turn's changes** sends [ActionsIntent.ShowTurnChanges]; it does not wait for a running
 *   action, since it only opens a page.
 * - **Fork from here** sends [ActionsIntent.Request] with [SessionAction.Fork] of [messageId]; it can
 *   be tapped only while [ActionsState.canAct]. The confirmation is drawn by [SessionActionsMenu],
 *   which the chat always shows, so it outlives these actions.
 * - **Close** only calls [onDismiss].
 *
 * Either action also calls [onDismiss], so the actions close once used.
 */
@Composable
fun MessageActions(
    messageId: MessageId,
    state: ActionsState,
    onIntent: (ActionsIntent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.xs, Alignment.End),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = {
                onIntent(ActionsIntent.ShowTurnChanges(messageId))
                onDismiss()
            },
            modifier = Modifier.testTag(ActionsTags.TURN_CHANGES),
        ) {
            Text(stringResource(R.string.chat_actions_turn_changes))
        }
        TextButton(
            onClick = {
                onIntent(ActionsIntent.Request(SessionAction.Fork(messageId)))
                onDismiss()
            },
            enabled = state.canAct,
            modifier = Modifier.testTag(ActionsTags.FORK_HERE),
        ) {
            Text(stringResource(R.string.chat_actions_fork_here))
        }
        IconButton(onClick = onDismiss, modifier = Modifier.testTag(ActionsTags.MESSAGE_CLOSE)) {
            Icon(imageVector = ActionsIcons.Close, contentDescription = stringResource(R.string.chat_actions_message_close))
        }
    }
}
