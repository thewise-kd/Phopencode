package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.VcsFile

/**
 * Read-only git information. Errors are described on [OpencodeClient].
 *
 * Git changes, and the staged/unstaged distinction, go over SSH: the server API has neither.
 */
interface VcsApi {
    /** Combines `/api/vcs` and `/api/vcs/base`, which the server keeps as two routes. */
    suspend fun current(location: Location): VcsBranchInfo

    suspend fun status(location: Location): List<VcsFile>

    /** Returns all files in one result, which a large file can inflate. */
    suspend fun diff(location: Location, query: VcsDiffQuery = VcsDiffQuery()): List<FileDiff>

    suspend fun branches(location: Location, search: String? = null, limit: Int? = null): List<VcsBranch>
}

/**
 * @property branch The checked-out branch, from `/api/vcs`. Null when there is none, as on a detached
 *   HEAD or with no repository.
 * @property defaultBranch The branch name from `/api/vcs/base`, which may be null. `/api/vcs` does not
 *   give it.
 */
data class VcsBranchInfo(
    val branch: String? = null,
    val defaultBranch: String? = null,
)

/**
 * A branch name: the branch route returns plain strings. To tell which one is checked out, compare with
 * [VcsBranchInfo.branch].
 */
data class VcsBranch(val name: String)

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
