package io.github.thewisekd.phopencode.feature.chat.ui.palette

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PromptAttachment

/**
 * Stub from the contract (P7.1); P7.9 draws the composer's attachments as chips above it, each with
 * a way to take it off, which calls [onDetach]; the chat sends that as `ComposerIntent.Detach`.
 * Draws nothing without attachments.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AttachmentChips(
    attachments: List<PromptAttachment>,
    onDetach: (PromptAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier)
}
