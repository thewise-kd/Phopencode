package io.github.thewisekd.phopencode.feature.chat.ui.palette

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PromptAttachment
import io.github.thewisekd.phopencode.feature.chat.ui.R

/** A long path or name is cut at this width, so one chip does not fill the row. */
private val NAME_MAX_WIDTH = 200.dp

/** The kind icon's square on a chip. */
private val CHIP_KIND_BOX = 28.dp

/**
 * The composer's attachments as chips above it, in the order attached, on one row that scrolls
 * sideways. Each chip shows its kind's icon (file, agent or skill), read as the kind in words before
 * what it holds, with a remove button whose label says what it removes; the button calls [onDetach], which the chat sends
 * as `ComposerIntent.Detach`. Paths and names are drawn, never logged.
 *
 * Draws nothing without attachments: only an empty box for [modifier], so the chat's slot stays in
 * place.
 */
@Composable
fun AttachmentChips(
    attachments: List<PromptAttachment>,
    onDetach: (PromptAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) {
        Box(modifier)
        return
    }
    val spacing = PhopencodeTheme.spacing
    Row(
        // The scroll comes last: padding after it would make scrolling a chip into view loop.
        modifier = modifier.padding(horizontal = spacing.sm).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { attachment ->
            key(PaletteTags.chip(attachment)) {
                AttachmentChip(attachment, onRemove = { onDetach(attachment) })
            }
        }
    }
}

@Composable
private fun AttachmentChip(attachment: PromptAttachment, onRemove: () -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val name = attachment.displayName()
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.padding(start = spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.testTag(PaletteTags.chip(attachment)).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KindIcon(attachment.kind(), stringResource(attachment.kindRes()), size = CHIP_KIND_BOX)
                Text(
                    text = name,
                    modifier = Modifier.widthIn(max = NAME_MAX_WIDTH),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.testTag(PaletteTags.chipRemove(attachment))) {
                Icon(
                    imageVector = PhopencodeIcons.Close,
                    contentDescription = stringResource(attachment.removeRes(), name),
                )
            }
        }
    }
}

private fun PromptAttachment.kind(): ItemKind = when (this) {
    is PromptAttachment.File -> ItemKind.File
    is PromptAttachment.Agent -> ItemKind.Agent
    is PromptAttachment.Skill -> ItemKind.Skill
}

private fun PromptAttachment.displayName(): String = when (this) {
    is PromptAttachment.File -> path
    is PromptAttachment.Agent -> id
    is PromptAttachment.Skill -> id
}

@StringRes
private fun PromptAttachment.kindRes(): Int = when (this) {
    is PromptAttachment.File -> R.string.chat_palette_kind_file
    is PromptAttachment.Agent -> R.string.chat_palette_kind_agent
    is PromptAttachment.Skill -> R.string.chat_palette_kind_skill
}

@StringRes
private fun PromptAttachment.removeRes(): Int = when (this) {
    is PromptAttachment.File -> R.string.chat_palette_remove_file
    is PromptAttachment.Agent -> R.string.chat_palette_remove_agent
    is PromptAttachment.Skill -> R.string.chat_palette_remove_skill
}
