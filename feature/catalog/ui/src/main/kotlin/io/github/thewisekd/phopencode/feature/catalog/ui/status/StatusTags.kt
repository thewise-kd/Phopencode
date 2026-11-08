package io.github.thewisekd.phopencode.feature.catalog.ui.status

/** Marks the scrolling list of MCP servers and plugins, for tests. */
internal const val STATUS_LIST_TAG = "catalog-status:list"

/**
 * Marks an MCP server's status badge. Carries the server's configured name, which is a key in the
 * server's configuration, never its address or error.
 */
internal fun mcpBadgeTag(name: String): String = "catalog-status:mcp-badge:$name"

/** Marks a plugin's status badge by its position in the list: a plugin's ID is optional. */
internal fun pluginBadgeTag(index: Int): String = "catalog-status:plugin-badge:$index"
