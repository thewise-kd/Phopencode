package io.github.thewisekd.phopencode.core.navigation

import kotlinx.serialization.Serializable

/**
 * Filters, such as the branch of the log, are state of the screen: a branch can be called `null`,
 * and only a non-null required argument keeps that text.
 */
sealed interface GitRoute : Route {
    @Serializable
    data class Status(val directory: String) : GitRoute

    /** @property staged Shows the index against HEAD instead of the working tree. */
    @Serializable
    data class FileDiff(val directory: String, val path: String, val staged: Boolean = false) : GitRoute

    @Serializable
    data class Log(val directory: String) : GitRoute

    /** @property commit The commit hash. */
    @Serializable
    data class CommitDetail(val directory: String, val commit: String) : GitRoute

    @Serializable
    data class CommitFileDiff(val directory: String, val commit: String, val path: String) : GitRoute

    /** Writing a commit. */
    @Serializable
    data class Commit(val directory: String) : GitRoute

    @Serializable
    data class Branches(val directory: String) : GitRoute

    /** Fetch, pull and push. */
    @Serializable
    data class Remote(val directory: String) : GitRoute
}
