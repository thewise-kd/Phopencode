package io.github.thewisekd.phopencode.core.api

/**
 * The folder a call works in: one server serves many folders.
 *
 * @property directory An absolute path on the server.
 */
data class Location(val directory: String)
