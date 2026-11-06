package io.github.thewisekd.phopencode.core.remote.detect

/** Everything detection found on a server. */
data class Detection(val binary: BinaryLookup, val server: ServerLookup)

/** Why a detection step has no answer. */
enum class DetectFailure {
    /** A remote command ran past its time limit and was ended. */
    TimedOut,

    /** A remote command printed more than detection reads. */
    OutputTooLarge,

    /** The server lacks every tool a step can use, for example both `/proc` and `ps`. */
    ToolsUnavailable,
}

/** Whether and where the opencode binary is installed. */
sealed interface BinaryLookup {
    /**
     * @property path The absolute path on the server, to run the binary by.
     * @property version What `--version` gave for this path.
     */
    data class Found(val path: String, val source: BinarySource, val version: VersionCheck) : BinaryLookup

    /** No lookup found opencode: not on the command or login `PATH`, nor in a known install folder. */
    data object Missing : BinaryLookup

    /** No lookup could finish, so whether opencode is installed is unknown. */
    data class Failed(val reason: DetectFailure) : BinaryLookup
}

enum class BinarySource {
    /** On the `PATH` of the shell that runs commands. */
    Path,

    /** On the `PATH` of the account's login shell only. */
    LoginPath,

    /** In a known install folder such as `~/.opencode/bin`, on no `PATH`. */
    KnownLocation,
}

/** The outcome of `opencode --version`. */
sealed interface VersionCheck {
    data class Known(val version: OpencodeVersion) : VersionCheck

    /** It succeeded, but printed no version this app can read. */
    data object Unparseable : VersionCheck

    /** It exited with a non-zero code, or with none and a [signal]. */
    data class Failed(val exitCode: Int?, val signal: String?) : VersionCheck

    data object TimedOut : VersionCheck
}

/** Whether an `opencode serve` of the login user runs and where the app can reach it. */
sealed interface ServerLookup {
    /**
     * A live `opencode serve` process listening on a loopback address, to reach through an SSH
     * tunnel. A port bound to any other address, a wildcard included, is never reported.
     *
     * @property host `127.x.y.z` or `::1`.
     */
    data class Running(
        val host: String,
        val port: Int,
        val pid: Long,
        val source: ServerSource,
        val notes: Set<ServerNote> = emptySet(),
    ) : ServerLookup

    /**
     * A live `opencode serve` of the login user holds the loopback port [host]:[port], but the port
     * did not accept a connection. The server runs, so nothing may be started next to it; and it
     * may be busy or stuck, so nothing may stop it either. Only trying again later can help.
     */
    data class Unresponsive(
        val host: String,
        val port: Int,
        val pid: Long,
        val source: ServerSource,
        val notes: Set<ServerNote> = emptySet(),
    ) : ServerLookup

    /**
     * Live `opencode serve` processes of the login user ([pids]) run, but none holds a loopback
     * port the app could reach: each listens only on another address (see
     * [ServerNote.NonLoopbackListener]), has no socket yet, or has one the tools cannot tie to it.
     * A server runs, so nothing may be started next to it, and nothing may stop it.
     */
    data class Unusable(val pids: Set<Long>, val notes: Set<ServerNote> = emptySet()) : ServerLookup

    /**
     * Asked for the server on loopback [port], live `opencode serve` processes of the login user
     * ([pids]) run, but none holds that port on any address. Servers on other ports are left alone:
     * nothing may be started next to them, and nothing may stop them.
     */
    data class NotOnPort(val port: Int, val pids: Set<Long>, val notes: Set<ServerNote> = emptySet()) : ServerLookup

    /** No live `opencode serve` process of the login user was seen. */
    data class NotRunning(val notes: Set<ServerNote> = emptySet()) : ServerLookup

    data class Failed(val reason: DetectFailure) : ServerLookup
}

enum class ServerSource {
    /** The state file written when the app started the server, after checking it. */
    StateFile,

    /** A scan of the login user's processes and listening sockets. */
    ProcessScan,
}

/** Things worth showing next to a server result. */
enum class ServerNote {
    /** The state file names a server that no longer runs, or no longer listens where it says. */
    StaleStateFile,

    /** The state file could not be read or is not valid; it was ignored. */
    InvalidStateFile,

    /** An `opencode serve` listens on an address that is not loopback; that port was not used. */
    NonLoopbackListener,

    /** A loopback port that a server process holds did not accept a connection. */
    PortDidNotAnswer,
}
