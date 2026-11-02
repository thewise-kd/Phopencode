package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.VcsFile

/**
 * Read-only git information. Errors are described on [OpencodeClient].
 *
 * Git changes, and the staged/unstaged distinction, go over SSH: the server API has neither.
 */
interface VcsApi {
    suspend fun current(location: Location): VcsBranchInfo

    suspend fun status(location: Location): List<VcsFile>

    /** Returns all files in one result, which a large file can inflate. */
    suspend fun diff(location: Location, query: VcsDiffQuery = VcsDiffQuery()): List<FileDiff>

    suspend fun branches(location: Location, search: String? = null, limit: Int? = null): List<VcsBranch>
}

/**
 * @property branch The checked-out branch. Null when there is none, as on a detached HEAD.
 * @property defaultBranch The repository's main branch, if the server knows it.
 */
data class VcsBranchInfo(
    val branch: String? = null,
    val defaultBranch: String? = null,
)

/** @property current Whether this branch is checked out. */
data class VcsBranch(
    val name: String,
    val current: Boolean = false,
)

/**
 * @property base What [VcsDiffMode.Branch] and [VcsDiffMode.Committed] compare with; null is the
 *   server's default.
 * @property context The number of unchanged lines around each change; null is the server's default.
 * @property untracked Whether to include untracked files; null is the server's default.
 */
data class VcsDiffQuery(
    val mode: VcsDiffMode = VcsDiffMode.Working,
    val base: String? = null,
    val context: Int? = null,
    val untracked: Boolean? = null,
)

enum class VcsDiffMode {
    /** Uncommitted changes. */
    Working,

    /** The whole branch, committed and uncommitted, against its base. */
    Branch,

    /** Only what the branch has committed since its base. */
    Committed,
}
