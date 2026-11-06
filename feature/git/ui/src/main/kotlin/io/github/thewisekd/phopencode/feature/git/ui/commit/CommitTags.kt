package io.github.thewisekd.phopencode.feature.git.ui.commit

/** Test tags of the commit screen. Fixed words only: never a path or the message. */
internal object CommitTags {
    const val REFRESH = "git_commit:refresh"
    const val LOADING = "git_commit:loading"
    const val BUSY = "git_commit:busy"
    const val LIST = "git_commit:list"
    const val MESSAGE = "git_commit:message"
    const val MESSAGE_PROBLEM = "git_commit:message_problem"
    const val COMMIT = "git_commit:commit"
    const val ERROR = "git_commit:error"
    const val HOOK_ERROR = "git_commit:hook_error"
    const val HOOK_OUTPUT = "git_commit:hook_output"
    const val EMPTY = "git_commit:empty"
    const val NOTHING_STAGED = "git_commit:nothing_staged"
    const val STAGE_ALL = "git_commit:stage_all"
    const val UNSTAGE_ALL = "git_commit:unstage_all"
    const val DISCARD = "git_commit:discard"
    const val DISCARD_DIALOG = "git_commit:discard_dialog"

    fun header(staged: Boolean): String = "git_commit:header:${group(staged)}"

    fun row(staged: Boolean): String = "git_commit:row:${group(staged)}"

    private fun group(staged: Boolean): String = if (staged) "staged" else "unstaged"
}
