package io.github.thewisekd.phopencode.feature.git.ui.log

/** Test tags of the history screen; its root keeps the `placeholder:<route>` tag instead. */
internal object LogTags {
    const val REFRESH = "git_log_refresh"
    const val LOADING = "git_log_loading"
    const val ERROR = "git_log_error"
    const val FILTER = "git_log_filter"
    const val LIST = "git_log_list"
    const val EMPTY = "git_log_empty"
    const val ROW = "git_log_row"
    const val MERGE = "git_log_merge"
    const val LOADING_MORE = "git_log_loading_more"
    const val MORE_ERROR = "git_log_more_error"
    const val END = "git_log_end"

    fun filterOption(branch: String?): String = "git_log_filter_option:${branch ?: ""}"
}

/** Test tags of the commit detail screen; its root keeps the `placeholder:<route>` tag instead. */
internal object CommitDetailTags {
    const val LOADING = "git_commit_detail_loading"
    const val ERROR = "git_commit_detail_error"
    const val LIST = "git_commit_detail_list"
    const val MESSAGE = "git_commit_detail_message"
    const val HASH = "git_commit_detail_hash"
    const val AUTHOR = "git_commit_detail_author"
    const val COMMITTER = "git_commit_detail_committer"
    const val PARENTS = "git_commit_detail_parents"
    const val FILES_HEADER = "git_commit_detail_files_header"
    const val NO_FILES = "git_commit_detail_no_files"
    const val FILE = "git_commit_detail_file"
    const val COUNTS = "git_commit_detail_counts"
}
