package io.github.thewisekd.phopencode.feature.chat.ui.palette

import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteItem
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PromptAttachment

/**
 * Test tags of the command palette and the attachment chips. A row's or chip's tag holds all of its
 * texts. Tags are for tests only: they hold names and paths, and are never logged.
 */
internal object PaletteTags {
    const val TITLE: String = "palette:title"
    const val CLOSE: String = "palette:close"
    const val NOTICE: String = "palette:notice"
    const val NOTICE_DISMISS: String = "palette:notice-dismiss"

    fun item(item: PaletteItem): String = "palette:item:" + when (item) {
        is PaletteItem.Command -> "command:${item.name}"
        is PaletteItem.Skill -> "skill:${item.id}:${item.name}"
        is PaletteItem.Agent -> "agent:${item.id}"
        is PaletteItem.File -> "file:${item.path}"
        is PaletteItem.BuiltIn -> "builtin:${item.command.primary}"
    }

    fun section(section: PaletteSection): String = "palette:section:${section.name}"

    fun chip(attachment: PromptAttachment): String = "palette:chip:${attachment.key()}"

    fun chipRemove(attachment: PromptAttachment): String = "palette:chip-remove:${attachment.key()}"

    private fun PromptAttachment.key(): String = when (this) {
        is PromptAttachment.File -> "file:$path"
        is PromptAttachment.Agent -> "agent:$id"
        is PromptAttachment.Skill -> "skill:$id"
    }
}
