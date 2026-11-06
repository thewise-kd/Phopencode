package io.github.thewisekd.phopencode.feature.git.ui.diff

/** Tags on the diff screen's parts, for tests. */
internal object DiffTags {
    const val LOADING = "git:diff:loading"
    const val ERROR = "git:diff:error"
    const val REFRESH = "git:diff:refresh"

    /** The box that scrolls every line sideways together. */
    const val LINES = "git:diff:lines"

    /** The list of the header, hunk headers and lines, which scrolls vertically. */
    const val LIST = "git:diff:list"

    const val PATH = "git:diff:path"
    const val OLD_PATH = "git:diff:old-path"
    const val KIND = "git:diff:kind"
    const val CONTEXT = "git:diff:context"
    const val COUNTS = "git:diff:counts"

    const val BINARY = "git:diff:binary"
    const val TOO_LARGE = "git:diff:too-large"
    const val UNCHANGED = "git:diff:unchanged"

    /** Parts of a line row; they merge into the row, so look for them in the unmerged tree. */
    const val OLD_NUMBER = "git:diff:line:old"
    const val NEW_NUMBER = "git:diff:line:new"
    const val MARKER = "git:diff:line:marker"
    const val TEXT = "git:diff:line:text"

    fun hunk(hunk: Int): String = "git:diff:hunk:$hunk"

    fun line(hunk: Int, line: Int): String = "git:diff:line:$hunk:$line"
}
