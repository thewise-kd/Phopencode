package io.github.thewisekd.phopencode.feature.chat.ui.revert

/** Test tags of the turn diff page. A file's tags hold its place in the list, never its path. */
internal object RevertTags {
    const val LIST: String = "revert:list"
    const val SUMMARY: String = "revert:summary"
    const val REVERT: String = "revert:revert"
    const val CONFIRMATION: String = "revert:confirmation"
    const val CONFIRM: String = "revert:confirm"
    const val CANCEL: String = "revert:cancel"
    const val OTHER_TURN: String = "revert:other-turn"
    const val PENDING: String = "revert:pending"

    /** The row of the file at [index], read as one: path, status and counts. */
    fun file(index: Int): String = "revert:file:$index"

    /** The diff text of the file at [index], when open and the server gave one. */
    fun diff(index: Int): String = "revert:diff:$index"

    /** What a cut diff of the file at [index] leaves out. */
    fun more(index: Int): String = "revert:more:$index"
}
