package io.github.thewisekd.phopencode.feature.chat.ui.actions

/** Test tags on the session's action menu and a prompt's actions. */
internal object ActionsTags {
    /** The icon button next to the usage bar that opens the menu. */
    const val MENU_BUTTON: String = "actions:menu_button"

    /** The still text while a compaction or a fork runs. */
    const val STATUS: String = "actions:status"

    /** The open menu's content, its title, its close button and its two actions. */
    const val MENU: String = "actions:menu"
    const val MENU_TITLE: String = "actions:menu_title"
    const val MENU_CLOSE: String = "actions:menu_close"
    const val COMPACT: String = "actions:compact"
    const val FORK: String = "actions:fork"

    /** The menu's note while an action runs. */
    const val BUSY: String = "actions:busy"

    /** The confirmation's content, its title, its message and its two buttons. */
    const val CONFIRM_DIALOG: String = "actions:confirm_dialog"
    const val CONFIRM_TITLE: String = "actions:confirm_title"
    const val CONFIRM_MESSAGE: String = "actions:confirm_message"
    const val CONFIRM: String = "actions:confirm"
    const val CANCEL: String = "actions:cancel"

    /** The failed action's banner, in the open menu or on its own. */
    const val ERROR: String = "actions:error"

    /** A prompt's actions. */
    const val TURN_CHANGES: String = "actions:turn_changes"
    const val FORK_HERE: String = "actions:fork_here"
    const val MESSAGE_CLOSE: String = "actions:message_close"
}
