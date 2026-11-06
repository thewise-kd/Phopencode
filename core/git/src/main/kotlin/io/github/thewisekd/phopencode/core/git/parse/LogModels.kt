package io.github.thewisekd.phopencode.core.git.parse

/**
 * One commit from [LogParser]. Times are Unix seconds. [toString] hides names, emails and the
 * message.
 */
public data class GitCommit(
    /** The full hash. */
    val hash: String,
    /** Full parent hashes in order; empty for a root commit, two or more for a merge. */
    val parents: List<String>,
    val authorName: String,
    val authorEmail: String,
    val authorTime: Long,
    val committerName: String,
    val committerEmail: String,
    val commitTime: Long,
    /** The subject: the first paragraph of the message, lines joined by spaces. */
    val subject: String,
    /** The rest of the message without trailing newlines; empty when there is none. */
    val body: String,
) {
    /** Whether the commit has more than one parent. */
    val isMerge: Boolean get() = parents.size > 1

    override fun toString(): String =
        "GitCommit(hash=$hash, parents=$parents, authorName=${mask(authorName)}, authorEmail=${mask(authorEmail)}, " +
            "authorTime=$authorTime, committerName=${mask(committerName)}, committerEmail=${mask(committerEmail)}, " +
            "commitTime=$commitTime, subject=${mask(subject)}, body=${mask(body)})"
}
