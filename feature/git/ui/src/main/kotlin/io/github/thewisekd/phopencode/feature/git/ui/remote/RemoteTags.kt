package io.github.thewisekd.phopencode.feature.git.ui.remote

/** Test tags of the remote screen; its root keeps the `placeholder:<route>` tag instead. */
internal object RemoteTags {
    const val REFRESH = "git_remote_refresh"
    const val LOADING = "git_remote_loading"
    const val ERROR = "git_remote_error"
    const val LIST = "git_remote_list"
    const val BRANCH = "git_remote_branch"
    const val UPSTREAM = "git_remote_upstream"
    const val NO_UPSTREAM = "git_remote_no_upstream"
    const val REMOTE = "git_remote_remote"
    const val NO_REMOTES = "git_remote_no_remotes"
    const val FETCH = "git_remote_fetch"
    const val PULL = "git_remote_pull"
    const val PUSH = "git_remote_push"
    const val RUNNING = "git_remote_running"
    const val CANCEL = "git_remote_cancel"
    const val RESULT = "git_remote_result"
    const val CONFIRM = "git_remote_confirm"

    /**
     * The choice of one remote. Remote names are the user's own: the tag stays inside the app's
     * semantics tree and is never logged.
     */
    fun remoteOption(name: String): String = "git_remote_option:$name"
}
