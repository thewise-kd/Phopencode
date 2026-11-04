package io.github.thewisekd.phopencode.core.remote.start

/**
 * Why `opencode serve` did not start. Messages are fixed text and there is never a cause: server
 * output, paths and the password stay out of logs. SSH failures other than a timeout arrive as
 * [io.github.thewisekd.phopencode.core.ssh.api.SshException].
 */
sealed class ServerStartException(message: String) : Exception(message) {
    /** No executable `opencode` was found: not the given path, not on `PATH`, not `~/.opencode/bin`. */
    class BinaryMissing : ServerStartException("opencode was not found on the server")

    /** The server exited and its output says the port is taken. */
    class PortInUse : ServerStartException("The server port is already in use")

    /** The server exited before it printed its ready line. */
    class Exited : ServerStartException("The server exited during startup")

    /** No ready line within the timeout; the server was stopped. */
    class Timeout : ServerStartException("The server did not start in time")

    /**
     * The output was not what a starting server prints, for example a ready line whose URL is not
     * `http://127.0.0.1:<port>`. A server that did start was stopped.
     */
    class UnexpectedOutput : ServerStartException("The server printed unexpected output")

    /** The state folder or file could not be written safely; a server that did start was stopped. */
    class StateNotWritten : ServerStartException("The server state could not be written")
}
