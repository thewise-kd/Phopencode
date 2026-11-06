package io.github.thewisekd.phopencode.feature.git.ui.status

/** Test tags of the status screen; its root keeps the `placeholder:<route>` tag instead. */
internal object StatusTags {
    const val REFRESH = "git_status_refresh"
    const val LOADING = "git_status_loading"
    const val REFRESHING = "git_status_refreshing"
    const val ERROR = "git_status_error"
    const val ACTION_ERROR = "git_status_action_error"
    const val BRANCH = "git_status_branch"
    const val UPSTREAM = "git_status_upstream"
    const val NO_UPSTREAM = "git_status_no_upstream"
    const val UPSTREAM_GONE = "git_status_upstream_gone"
    const val COMMIT = "git_status_commit"
    const val LOG = "git_status_log"
    const val BRANCHES = "git_status_branches"
    const val REMOTE = "git_status_remote"
    const val LIST = "git_status_list"
    const val CLEAN = "git_status_clean"
    const val STAGE_ALL = "git_status_stage_all"
    const val UNSTAGE_ALL = "git_status_unstage_all"

    fun header(group: StatusGroup): String = "git_status_header_${group.name}"

    fun row(group: StatusGroup): String = "git_status_row_${group.name}"
}
