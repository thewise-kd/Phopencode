package io.github.thewisekd.phopencode.feature.catalog.logic.contract

import io.github.thewisekd.phopencode.core.api.McpServer
import io.github.thewisekd.phopencode.core.api.Plugin

/**
 * MCP servers and plugins, `CatalogRoute.Status` (P10.6 logic, P10.7 screen): each with its status
 * badge and, when it failed, the error in the server's words on its row. View only: nothing here
 * connects, disables or authorizes anything.
 *
 * Where it comes from: [CatalogConnection], read fresh each time, never kept, since a status changes
 * on the server; the two lists are read apart, so one that fails leaves the other. Going back to the
 * screen reads both again ([StatusIntent.Refresh]).
 *
 * The errors ([McpServer.error], [Plugin.error]) can quote a URL with a token or a path: they are
 * shown, never printed or logged; both types leave them out of `toString`.
 */
data class StatusState(
    val mcp: CatalogList<McpServer> = CatalogList(),
    val plugins: CatalogList<Plugin> = CatalogList(),
)

sealed interface StatusIntent {
    /** Reads both lists again, as when the screen is shown again, a pull to refresh or a retry. */
    data object Refresh : StatusIntent

    data object Close : StatusIntent
}

sealed interface StatusEvent {
    data object Close : StatusEvent
}

interface StatusPresenter : Presenter<StatusState, StatusIntent, StatusEvent>
