package io.github.thewisekd.phopencode.core.navigation

import kotlinx.serialization.Serializable

/** Delete, rename and new folder are dialogs on [Tree], so they have no route. */
sealed interface FilesRoute : Route {
    /** Starts at [directory]. Moving between folders is state of the screen, not a new route. */
    @Serializable
    data class Tree(val directory: String) : FilesRoute

    /** @property line The 1-based line to show, as a search result gives it. */
    @Serializable
    data class Viewer(val directory: String, val path: String, val line: Int? = null) : FilesRoute

    @Serializable
    data class Editor(val directory: String, val path: String) : FilesRoute

    @Serializable
    data class Search(val directory: String) : FilesRoute

    /** @property path The folder to upload to; [directory] itself when at the root. */
    @Serializable
    data class Transfer(val directory: String, val path: String) : FilesRoute
}
