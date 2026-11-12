package io.github.thewisekd.phopencode.core.navigation

import kotlinx.serialization.Serializable

/**
 * The live preview (P13): a web frontend the developer runs on the server's loopback, shown on the
 * phone through the active connection. Neither route names a host: the target is always the
 * server's loopback.
 */
sealed interface PreviewRoute : Route {
    /** Lists the server's listening ports, to pick the one to preview. */
    @Serializable
    data object Ports : PreviewRoute

    /**
     * The page served on [port] of the server's loopback. A port outside 1..65535 is refused here,
     * so no route, from code or from saved state, can name one.
     */
    @Serializable
    data class Page(val port: Int) : PreviewRoute {
        init {
            require(port in 1..MAX_PORT) { "Port is out of range" }
        }
    }
}

private const val MAX_PORT = 65535
