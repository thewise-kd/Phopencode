package io.github.thewisekd.phopencode.feature.preview.ui.page.console

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel

/** Test tags of the preview's console panel and its toolbar button. */
internal object ConsoleTags {
    const val BUTTON = "preview_console_button"
    const val BADGE = "preview_console_badge"
    const val PANEL = "preview_console_panel"
    const val CLEAR = "preview_console_clear"
    const val HIDE = "preview_console_hide"
    const val LIST = "preview_console_list"
    const val EMPTY = "preview_console_empty"

    /** The filter chip of [level]; null is the chip that shows every level. */
    fun filter(level: ConsoleLevel?): String = "preview_console_filter_${level?.name ?: "all"}"

    /** The entry at [index] of what the panel lists under its filter. */
    fun entry(index: Int): String = "preview_console_entry_$index"
}
