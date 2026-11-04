package io.github.thewisekd.phopencode.core.remote.detect

import io.github.thewisekd.phopencode.core.remote.state.ServerState
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.ExecResult
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Time limits of the remote commands detection runs. */
data class DetectTimeouts(
    val locate: Duration = 15.seconds,
    val loginLocate: Duration = 10.seconds,
    val version: Duration = 20.seconds,
    val readState: Duration = 10.seconds,
    val scan: Duration = 30.seconds,
) {
    init {
        require(listOf(locate, loginLocate, version, readState, scan).all { it.isPositive() && it.isFinite() }) {
            "Timeouts must be positive"
        }
    }
}

/**
 * Finds the opencode binary and a running `opencode serve` on the server, through [exec] alone.
 * It changes nothing there and logs nothing.
 *
 * Every outcome on the server is a result, including a missing binary, an odd version, a timed-out
 * command and a missing tool. Only a lost connection ([SshException.Disconnected]) and other
 * connection failures are thrown, because then nothing can be said about the server.
 *
 * A port is reported only when the server's own tools show it bound to loopback by a live
 * `opencode serve` of the login user; a port that merely answers is never enough.
 *
 * @param portProbe Also checks that such a port accepts connections. Its
 *   [SshException.Disconnected] is thrown like one from [exec].
 * @param stateFile Where the server startup writes its [ServerState].
 */
class ServerDetector(
    private val exec: RemoteExec,
    private val portProbe: PortProbe? = null,
    private val stateFile: StateFileLocation = StateFileLocation.DEFAULT,
    private val timeouts: DetectTimeouts = DetectTimeouts(),
) {
    suspend fun detect(): Detection = Detection(findBinary(), findServer())

    suspend fun findBinary(): BinaryLookup {
        val located = run(DetectCommands.LOCATE, timeouts.locate, MAX_LOCATE_BYTES)
        val login = run(DetectCommands.LOGIN_LOCATE, timeouts.loginLocate, MAX_LOCATE_BYTES)

        val candidates = LinkedHashMap<String, BinarySource>()
        fun add(path: String, source: BinarySource) {
            if (isOpencodePath(path)) candidates.putIfAbsent(path, source)
        }
        val locatedLines = (located as? Outcome.Done)?.stdout?.lines().orEmpty()
        locatedLines.filter { it.startsWith(PATH_PREFIX) }.forEach { add(it.removePrefix(PATH_PREFIX), BinarySource.Path) }
        (login as? Outcome.Done)?.takeIf { it.result.succeeded }?.stdout?.lines()?.lastOrNull { it.isNotEmpty() }
            ?.let { add(it, BinarySource.LoginPath) }
        locatedLines.filter { it.startsWith(KNOWN_PREFIX) }
            .forEach { add(it.removePrefix(KNOWN_PREFIX), BinarySource.KnownLocation) }

        if (candidates.isEmpty()) {
            val failure = listOf(located, login).firstNotNullOfOrNull { (it as? Outcome.Failed)?.reason }
            return if (failure != null) BinaryLookup.Failed(failure) else BinaryLookup.Missing
        }

        var first: BinaryLookup.Found? = null
        for ((path, source) in candidates.entries.take(MAX_CANDIDATES)) {
            val found = BinaryLookup.Found(path, source, checkVersion(path))
            if (found.version is VersionCheck.Known) return found
            if (first == null) first = found
        }
        return checkNotNull(first)
    }

    suspend fun findServer(): ServerLookup {
        val state = readState()
        val report = when (val scanned = run(DetectCommands.SCAN, timeouts.scan, MAX_SCAN_BYTES)) {
            is Outcome.Failed -> return ServerLookup.Failed(scanned.reason)
            is Outcome.Done -> ScanReport.parse(scanned.stdout)
        }
        if (!report.processToolSeen) return ServerLookup.Failed(DetectFailure.ToolsUnavailable)

        val notes = linkedSetOf<ServerNote>()
        val probed = mutableSetOf<Pair<String, Int>>()
        // A live server that holds its loopback port but does not answer. It is reported only when
        // no port answers, and never as not running: nothing may be started next to it.
        var unresponsive: ServerLookup.Unresponsive? = null
        when (state) {
            StateRead.Absent -> Unit
            StateRead.Invalid -> notes += ServerNote.InvalidStateFile
            is StateRead.Valid -> when (val checked = checkState(state.state, report, notes, probed)) {
                is ServerLookup.Running -> return checked
                is ServerLookup.Unresponsive -> unresponsive = checked
                else -> {
                    // A live server process whose sockets nothing can show is unknown, not stale.
                    val unverifiable = state.state.pid in report.processes && !report.listenerToolSeen
                    if (!unverifiable) notes += ServerNote.StaleStateFile
                }
            }
        }

        val own = report.listeners.filter { it.pid != null && it.pid in report.processes }
        if (own.any { ListenerAddress.loopbackHost(it.host) == null }) notes += ServerNote.NonLoopbackListener
        val loopback = own.filter { ListenerAddress.loopbackHost(it.host) == it.host }
            .distinct()
            .sortedWith(compareBy({ it.pid }, { it.port }, { it.host }))
        for (listener in loopback) {
            if (!probed.add(listener.host to listener.port)) continue
            if (answers(listener.host, listener.port)) {
                return ServerLookup.Running(listener.host, listener.port, checkNotNull(listener.pid), ServerSource.ProcessScan, notes)
            }
            notes += ServerNote.PortDidNotAnswer
            if (unresponsive == null) {
                unresponsive = ServerLookup.Unresponsive(
                    listener.host,
                    listener.port,
                    checkNotNull(listener.pid),
                    ServerSource.ProcessScan,
                )
            }
        }
        unresponsive?.let { return it.copy(notes = notes) }
        if (report.processes.isNotEmpty() && !report.listenerToolSeen) return ServerLookup.Failed(DetectFailure.ToolsUnavailable)
        // A live server that holds no loopback port is still a server: never reported as absent,
        // so that nothing is started next to it.
        if (report.processes.isNotEmpty()) return ServerLookup.Unusable(report.processes.toSet(), notes)
        return ServerLookup.NotRunning(notes)
    }

    /**
     * The state's server when the scan confirms it: [ServerLookup.Running] when its port answers,
     * [ServerLookup.Unresponsive] when its live process holds the port but the port does not
     * answer. Null when the file cannot be confirmed.
     */
    private suspend fun checkState(
        state: ServerState,
        report: ScanReport,
        notes: MutableSet<ServerNote>,
        probed: MutableSet<Pair<String, Int>>,
    ): ServerLookup? {
        if (state.pid !in report.processes) return null
        // An answering port shows that something listens, not what: without a tool that ties the
        // socket to the process, the file is never trusted.
        if (!report.listenerToolSeen) return null
        val bound = report.listeners.any { it.pid == state.pid && it.host == state.host && it.port == state.port }
        if (!bound) return null
        probed += state.host to state.port
        if (!answers(state.host, state.port)) {
            notes += ServerNote.PortDidNotAnswer
            return ServerLookup.Unresponsive(state.host, state.port, state.pid, ServerSource.StateFile)
        }
        return ServerLookup.Running(state.host, state.port, state.pid, ServerSource.StateFile, notes)
    }

    /** Only a refused or timed-out connection is a port that did not answer; a lost session is thrown. */
    private suspend fun answers(host: String, port: Int): Boolean {
        val probe = portProbe ?: return true
        return try {
            probe.answers(host, port)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SshException.Timeout) {
            false
        } catch (lost: SshException) {
            throw lost
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun readState(): StateRead {
        val outcome = run(DetectCommands.readState(stateFile), timeouts.readState, MAX_STATE_OUTPUT_BYTES)
        if (outcome !is Outcome.Done) return StateRead.Invalid
        val text = outcome.stdout
        val marker = text.substringBefore('\n')
        return when {
            marker == ABSENT && text.substringAfter('\n', "").isBlank() -> StateRead.Absent
            marker == PRESENT -> ServerState.parse(text.substringAfter('\n', ""))?.let(StateRead::Valid) ?: StateRead.Invalid
            else -> StateRead.Invalid
        }
    }

    private suspend fun checkVersion(path: String): VersionCheck {
        return when (val outcome = run(DetectCommands.version(path), timeouts.version, MAX_VERSION_BYTES)) {
            is Outcome.Failed -> if (outcome.reason == DetectFailure.TimedOut) VersionCheck.TimedOut else VersionCheck.Unparseable
            is Outcome.Done -> {
                val result = outcome.result
                when {
                    !result.succeeded -> VersionCheck.Failed(result.exitCode, result.exitSignal)
                    else -> (OpencodeVersion.find(outcome.stdout) ?: OpencodeVersion.find(outcome.stderr))
                        ?.let(VersionCheck::Known)
                        ?: VersionCheck.Unparseable
                }
            }
        }
    }

    /**
     * Runs one command with stdin closed. Timeouts and floods are outcomes; everything else about
     * the connection is thrown. The output's bytes are wiped once read: a state file is the user's.
     */
    private suspend fun run(command: String, timeout: Duration, maxBytes: Long): Outcome {
        val result = try {
            exec.run(ExecRequest(command, stdin = null, timeout = timeout), maxBytes)
        } catch (_: SshException.Timeout) {
            return Outcome.Failed(DetectFailure.TimedOut)
        } catch (_: SshException.OutputLimitExceeded) {
            return Outcome.Failed(DetectFailure.OutputTooLarge)
        }
        val stdout = result.stdout.decodeToString()
        val stderr = result.stderr.decodeToString()
        result.stdout.fill(0)
        result.stderr.fill(0)
        return Outcome.Done(result, stdout, stderr)
    }

    private fun String.lines(): List<String> = split('\n').map { it.removeSuffix("\r") }

    private sealed interface Outcome {
        class Done(val result: ExecResult, val stdout: String, val stderr: String) : Outcome

        class Failed(val reason: DetectFailure) : Outcome
    }

    private sealed interface StateRead {
        data object Absent : StateRead

        data object Invalid : StateRead

        class Valid(val state: ServerState) : StateRead
    }

    companion object {
        /** A detector for [session], which checks reported ports through the session's tunnel. */
        fun forSession(
            session: SshSession,
            stateFile: StateFileLocation = StateFileLocation.DEFAULT,
            timeouts: DetectTimeouts = DetectTimeouts(),
        ): ServerDetector = ServerDetector(
            session.exec,
            SocketPortProbe(session.socketFactory(), connected = { session.connected.value }),
            stateFile,
            timeouts,
        )

        private const val PATH_PREFIX = "path "
        private const val KNOWN_PREFIX = "known "
        private const val ABSENT = "absent"
        private const val PRESENT = "present"
        private const val MAX_CANDIDATES = 8
        private const val MAX_PATH_BYTES = 4096
        private const val MAX_LOCATE_BYTES = 64L * 1024
        private const val MAX_VERSION_BYTES = 64L * 1024
        private const val MAX_STATE_OUTPUT_BYTES = ServerState.MAX_BYTES + 64L
        private const val MAX_SCAN_BYTES = 4L * 1024 * 1024

        /** An absolute path whose last part is `opencode`, free of control characters and valid UTF-8. */
        internal fun isOpencodePath(path: String): Boolean =
            path.startsWith('/') &&
                path.substringAfterLast('/') == "opencode" &&
                path.none { it < ' ' || it == '\u007F' || it == '�' } &&
                path.encodeToByteArray().size <= MAX_PATH_BYTES
    }
}
