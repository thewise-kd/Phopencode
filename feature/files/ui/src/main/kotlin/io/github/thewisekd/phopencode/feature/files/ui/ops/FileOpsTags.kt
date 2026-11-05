package io.github.thewisekd.phopencode.feature.files.ui.ops

import io.github.thewisekd.phopencode.feature.files.logic.contract.FileAction

/** Tags on the parts of the file operations, for tests. */
internal object FileOpsTags {
    const val MENU = "files:ops:menu"
    const val MENU_TITLE = "files:ops:menu:title"
    const val MENU_CANCEL = "files:ops:menu:cancel"
    const val ACTION_EDIT = "files:ops:action:edit"
    const val ACTION_DOWNLOAD = "files:ops:action:download"
    const val ACTION_RENAME = "files:ops:action:rename"
    const val ACTION_DELETE = "files:ops:action:delete"
    const val TITLE = "files:ops:title"
    const val NAME_DIALOG = "files:ops:name"
    const val NAME_FIELD = "files:ops:name:field"
    const val NAME_PROBLEM = "files:ops:name:problem"
    const val SUBMIT = "files:ops:submit"
    const val CANCEL = "files:ops:cancel"
    const val DELETE_DIALOG = "files:ops:delete"
    const val DELETE_MESSAGE = "files:ops:delete:message"
    const val DELETE_CONFIRM = "files:ops:delete:confirm"
    const val BUSY = "files:ops:busy"
    const val ERROR = "files:ops:error"
    const val ERROR_DIALOG = "files:ops:error-dialog"
    const val ERROR_OK = "files:ops:error-dialog:ok"
}

/** The tag of the menu item for this action. */
internal val FileAction.tag: String
    get() = when (this) {
        FileAction.Edit -> FileOpsTags.ACTION_EDIT
        FileAction.Download -> FileOpsTags.ACTION_DOWNLOAD
        FileAction.Rename -> FileOpsTags.ACTION_RENAME
        FileAction.Delete -> FileOpsTags.ACTION_DELETE
    }
