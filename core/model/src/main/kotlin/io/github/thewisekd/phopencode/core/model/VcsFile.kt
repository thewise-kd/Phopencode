package io.github.thewisekd.phopencode.core.model

/** A file with changes. Line counts are null when there are none to give, as for a binary file. */
data class VcsFile(
    val path: String,
    val status: VcsFileStatus,
    val additions: Long? = null,
    val deletions: Long? = null,
)

sealed interface VcsFileStatus {
    data object Added : VcsFileStatus

    data object Modified : VcsFileStatus

    data object Deleted : VcsFileStatus

    data object Renamed : VcsFileStatus

    data object Untracked : VcsFileStatus

    data class Unknown(val raw: String) : VcsFileStatus
}
