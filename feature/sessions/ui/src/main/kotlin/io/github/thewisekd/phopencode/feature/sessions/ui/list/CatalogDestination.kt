package io.github.thewisekd.phopencode.feature.sessions.ui.list

/**
 * The catalog screens the session list's top bar menu opens. The screen names where to go; the
 * sessions graph turns it into a route, so the screen never sees the navigation controller.
 */
enum class CatalogDestination {
    /** The skills. */
    Skills,

    /** The commands, agents and models. */
    Registry,

    /** The status of the MCP servers and plugins. */
    Status,
}
