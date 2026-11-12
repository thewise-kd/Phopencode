package io.github.thewisekd.phopencode.feature.preview.logic.contract

/** Ports are TCP ports: 1..65535. */
internal const val MAX_PORT = 65535

internal fun requirePort(port: Int) {
    require(port in 1..MAX_PORT) { "Port is out of range" }
}

/**
 * A TCP port that takes connections on the server's loopback.
 *
 * @property isOpencode The opencode server listens here; the preview never opens it.
 */
data class PreviewPort(val port: Int, val isOpencode: Boolean) {
    init {
        requirePort(port)
    }
}

/** What listing the server's ports gave, when it worked. */
sealed interface PreviewPortList {
    /** @property ports Ascending, each port once; may be empty. */
    data class Listed(val ports: List<PreviewPort>) : PreviewPortList

    /** The server offers no way to list its ports; the user can still enter one by hand. */
    data object Unsupported : PreviewPortList
}

/**
 * The listening ports of the server of the active connection, read-only (P13.4's
 * `ListeningPortLister` with the opencode server's port marked, wired by P13.7). Runs nothing but
 * the fixed listing; never starts, stops or signals a process and never uses `sudo`.
 */
interface PreviewPortsSource {
    /**
     * @throws PreviewException with [PreviewFailure.NotConnected] without a connection,
     *   [PreviewFailure.ConnectionLost], [PreviewFailure.TimedOut], [PreviewFailure.OutputTooLarge]
     *   or [PreviewFailure.CommandFailed] when listing failed.
     */
    suspend fun list(): PreviewPortList
}
