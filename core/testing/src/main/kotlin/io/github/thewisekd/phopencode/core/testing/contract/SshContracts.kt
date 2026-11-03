package io.github.thewisekd.phopencode.core.testing.contract

import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import java.net.InetSocketAddress
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

interface HostKeyVerifierHarness {
    val verifier: HostKeyVerifier
    val target: SshTarget

    suspend fun givenKnown(key: HostKey)

    /** What the user answers when asked about an unknown key. */
    suspend fun givenUserAnswers(decision: HostKeyDecision)

    /** How many times the user was asked. */
    suspend fun timesAsked(): Int
}

private val KEY = HostKey("ssh-ed25519", byteArrayOf(1, 2, 3))
private val OTHER_KEY = HostKey("ssh-ed25519", byteArrayOf(4, 5, 6))

/** The policy on [HostKeyVerifier]. */
fun hostKeyVerifierContract(newHarness: suspend () -> HostKeyVerifierHarness): List<ContractCase> = contractCases {
    case("a known key is accepted without asking") {
        val harness = newHarness()
        harness.givenKnown(KEY)
        expectEqual(HostKeyDecision.Accept, harness.verifier.verify(harness.target, KEY), "decision")
        expectEqual(0, harness.timesAsked(), "times asked")
    }
    HostKeyDecision.entries.forEach { answer ->
        case("an unknown key asks the user, who says $answer") {
            val harness = newHarness()
            harness.givenUserAnswers(answer)
            expectEqual(answer, harness.verifier.verify(harness.target, KEY), "decision")
            expectEqual(1, harness.timesAsked(), "times asked")
        }
    }
    case("a key that differs from the stored one is rejected without offering to continue") {
        val harness = newHarness()
        harness.givenKnown(KEY)
        harness.givenUserAnswers(HostKeyDecision.Accept)
        expectEqual(HostKeyDecision.Reject, harness.verifier.verify(harness.target, OTHER_KEY), "decision")
        expectEqual(0, harness.timesAsked(), "times asked")
    }
}

interface SshConnectorHarness {
    val connector: SshConnector
    val target: SshTarget

    /** The key the server at [target] presents. */
    val serverKey: HostKey

    /** A credential the server accepts. */
    fun goodCredential(): SshCredential

    /** A credential the server refuses. */
    fun badCredential(): SshCredential

    /** A target nothing listens on. */
    val unreachable: SshTarget

    /** The server accepts connections and then stays silent. */
    suspend fun givenSilentServer()

    /** How many credentials the server received. */
    suspend fun credentialsReceived(): Int

    /** Connections that are open but did not become a session. */
    suspend fun halfOpenConnections(): Int
}

private val ACCEPT = HostKeyVerifier { _, _ -> HostKeyDecision.Accept }
private val REJECT = HostKeyVerifier { _, _ -> HostKeyDecision.Reject }

/** The promises on [SshConnector]. */
fun sshConnectorContract(newHarness: suspend () -> SshConnectorHarness): List<ContractCase> = contractCases {
    case("connect returns a connected session after Accept and login") {
        val harness = newHarness()
        harness.connector.connect(harness.target, harness.goodCredential(), ACCEPT).use { session ->
            expectThat(session.connected.value, "The session must be connected")
        }
    }
    case("the verifier sees the target and the key the server presented") {
        val harness = newHarness()
        var seen: Pair<SshTarget, HostKey>? = null
        val verifier = HostKeyVerifier { target, key ->
            seen = target to key
            HostKeyDecision.Accept
        }
        harness.connector.connect(harness.target, harness.goodCredential(), verifier).close()
        expectEqual(harness.target to harness.serverKey, seen, "verifier input")
    }
    case("a rejection fails with the presented key and sends no credential") {
        val harness = newHarness()
        val failure = expectThrows<SshException.HostKeyRejected>("connect") {
            harness.connector.connect(harness.target, harness.goodCredential(), REJECT)
        }
        expectEqual(harness.serverKey, failure.hostKey, "key")
        expectEqual(0, harness.credentialsReceived(), "credentials sent")
    }
    case("a verifier that throws counts as a rejection") {
        val harness = newHarness()
        val throwing = HostKeyVerifier { _, _ -> throw IllegalStateException("verifier failed") }
        expectThrows<SshException.HostKeyRejected>("connect") {
            harness.connector.connect(harness.target, harness.goodCredential(), throwing)
        }
        expectEqual(0, harness.credentialsReceived(), "credentials sent")
    }
    case("no credential is sent while the verifier is deciding") {
        val harness = newHarness()
        val asked = CompletableDeferred<Unit>()
        val decide = CompletableDeferred<HostKeyDecision>()
        val verifier = HostKeyVerifier { _, _ ->
            asked.complete(Unit)
            decide.await()
        }
        val connecting = start { harness.connector.connect(harness.target, harness.goodCredential(), verifier) }
        withinTime("verifier") { asked.await() }
        expectEqual(0, harness.credentialsReceived(), "credentials sent")
        decide.complete(HostKeyDecision.Accept)
        connecting.result("connect").getOrThrow().close()
        expectEqual(1, harness.credentialsReceived(), "credentials sent after Accept")
    }
    case("the key is checked again on every connect") {
        val harness = newHarness()
        var checks = 0
        val verifier = HostKeyVerifier { _, _ ->
            checks++
            if (checks == 1) HostKeyDecision.Reject else HostKeyDecision.Accept
        }
        expectThrows<SshException.HostKeyRejected>("first connect") {
            harness.connector.connect(harness.target, harness.goodCredential(), verifier)
        }
        harness.connector.connect(harness.target, harness.goodCredential(), verifier).close()
        expectEqual(2, checks, "checks")
    }
    case("time spent with the verifier does not count against the connect timeout") {
        val harness = newHarness()
        val slow = HostKeyVerifier { _, _ ->
            withContext(Dispatchers.Default) { delay(SHORT_TIMEOUT * 3) }
            HostKeyDecision.Accept
        }
        harness.connector.connect(
            harness.target,
            harness.goodCredential(),
            slow,
            SshConnectOptions(connectTimeout = SHORT_TIMEOUT),
        ).close()
    }
    case("a refused credential fails with AuthenticationFailed") {
        val harness = newHarness()
        expectThrows<SshException.AuthenticationFailed>("connect") {
            harness.connector.connect(harness.target, harness.badCredential(), ACCEPT)
        }
        expectEqual(0, harness.halfOpenConnections(), "half-open connections")
    }
    case("a target nothing listens on fails with ConnectionFailed") {
        val harness = newHarness()
        expectThrows<SshException.ConnectionFailed>("connect") {
            harness.connector.connect(harness.unreachable, harness.goodCredential(), ACCEPT)
        }
    }
    case("a silent server fails with Timeout") {
        val harness = newHarness()
        harness.givenSilentServer()
        expectThrows<SshException.Timeout>("connect") {
            harness.connector.connect(
                harness.target,
                harness.goodCredential(),
                ACCEPT,
                SshConnectOptions(connectTimeout = SHORT_TIMEOUT),
            )
        }
        expectEqual(0, harness.halfOpenConnections(), "half-open connections")
    }
    case("the session keeps working after the caller wiped the credential") {
        val harness = newHarness()
        val credential = harness.goodCredential()
        harness.connector.connect(harness.target, credential, ACCEPT).use { session ->
            credential.wipe()
            expectThat(session.connected.value, "The session must stay connected")
        }
    }
    case("cancelling closes the half-open connection") {
        val harness = newHarness()
        val asked = CompletableDeferred<Unit>()
        val verifier = HostKeyVerifier { _, _ ->
            asked.complete(Unit)
            awaitCancellation()
        }
        val connecting = start { harness.connector.connect(harness.target, harness.goodCredential(), verifier) }
        withinTime("verifier") { asked.await() }
        connecting.cancel()
        val failure = connecting.failure("cancelled connect")
        expectThat(failure is CancellationException, "Cancelling must not become <$failure>")
        expectEqual(0, harness.halfOpenConnections(), "half-open connections")
    }
}

private val SHORT_TIMEOUT = 200.milliseconds

// Real shell commands, so that a real implementation runs them unchanged. The fake's harness
// scripts each one.

/** Prints `out` and `err` and exits with 3. */
const val EXEC_PRINTS_AND_FAILS = "printf out; printf err >&2; exit 3"

/** Kills its own shell. */
const val EXEC_KILLS_ITSELF = "kill -KILL \$\$"

/** Copies stdin to stdout. */
const val EXEC_CAT = "cat"

/** Prints `ready`, then keeps running. */
const val EXEC_RUNS_ON = "printf ready; exec sleep 600"

/** Prints 5 bytes to stdout and to stderr. */
const val EXEC_FIVE_EACH = "printf 12345; printf 12345 >&2"

/** Prints 6 bytes to stdout, then keeps running. */
const val EXEC_FLOODS_STDOUT = "printf 123456; exec sleep 600"

/** Prints 6 bytes to stderr, then keeps running. */
const val EXEC_FLOODS_STDERR = "printf 123456 >&2; exec sleep 600"

interface RemoteExecHarness {
    val exec: RemoteExec

    /** Whether a process of this command runs on the server. */
    suspend fun isRunning(command: String): Boolean

    /** Waits until a process of this command runs on the server. */
    suspend fun awaitRunning(command: String)

    /** How many processes of this command the server started. */
    suspend fun timesStarted(command: String): Int

    /** Ends the connection. */
    suspend fun disconnect()
}

private fun collectOutput(events: List<ExecEvent>, select: (ExecEvent) -> ByteArray?): String =
    events.mapNotNull(select).fold(ByteArray(0), ByteArray::plus).decodeToString()

private fun stdoutOf(event: ExecEvent) = (event as? ExecEvent.Stdout)?.bytes

private fun stderrOf(event: ExecEvent) = (event as? ExecEvent.Stderr)?.bytes

/** The promises on [RemoteExec]. */
fun remoteExecContract(newHarness: suspend () -> RemoteExecHarness): List<ContractCase> = contractCases {
    case("run collects both outputs, and a non-zero exit is a result") {
        val result = newHarness().exec.run(ExecRequest(EXEC_PRINTS_AND_FAILS))
        expectEqual("out", result.stdout.decodeToString(), "stdout")
        expectEqual("err", result.stderr.decodeToString(), "stderr")
        expectEqual(3, result.exitCode, "exit code")
        expectThat(!result.succeeded, "Exit 3 is not success")
    }
    case("a process killed by a signal has a signal and no exit code") {
        val result = newHarness().exec.run(ExecRequest(EXEC_KILLS_ITSELF))
        expectEqual(null, result.exitCode, "exit code")
        expectEqual("KILL", result.exitSignal, "signal")
    }
    case("stdin reaches the process byte for byte") {
        val bytes = byteArrayOf(0, -1, 65, 10)
        val result = newHarness().exec.run(ExecRequest(EXEC_CAT, stdin = bytes))
        expectThat(result.stdout.contentEquals(bytes), "stdin changed on the way")
    }
    case("a null stdin closes at once, so a reading command sees end of input") {
        val result = newHarness().exec.run(ExecRequest(EXEC_CAT))
        expectEqual(0, result.exitCode, "exit code")
        expectEqual(0, result.stdout.size, "stdout size")
    }
    case("the output limit applies to stdout and stderr, each") {
        val result = newHarness().exec.run(ExecRequest(EXEC_FIVE_EACH), maxOutputBytes = 5)
        expectEqual(0, result.exitCode, "exit code")
    }
    listOf(EXEC_FLOODS_STDOUT to "stdout", EXEC_FLOODS_STDERR to "stderr").forEach { (command, stream) ->
        case("exceeding the limit on $stream fails and ends the process") {
            val harness = newHarness()
            val failure = expectThrows<SshException.OutputLimitExceeded>("run") {
                harness.exec.run(ExecRequest(command), maxOutputBytes = 5)
            }
            expectEqual(5L, failure.limitBytes, "limit")
            expectThat(!harness.isRunning(command), "The limit must end the process")
        }
    }
    case("a timeout of run is a real failure and ends the process") {
        val harness = newHarness()
        expectThrows<SshException.Timeout>("run") {
            harness.exec.run(ExecRequest(EXEC_RUNS_ON, timeout = SHORT_TIMEOUT))
        }
        expectThat(!harness.isRunning(EXEC_RUNS_ON), "The timeout must end the process")
    }
    case("no timeout means no limit") {
        val harness = newHarness()
        val running = start { harness.exec.run(ExecRequest(EXEC_RUNS_ON, timeout = null)) }
        harness.awaitRunning(EXEC_RUNS_ON)
        testScheduler.advanceTimeBy(1.hours)
        testScheduler.runCurrent()
        expectThat(!running.isFinished, "A command with no timeout keeps running")
        running.cancel()
        running.result("cancelled run")
    }
    case("cancelling run ends the process") {
        val harness = newHarness()
        val running = start { harness.exec.run(ExecRequest(EXEC_RUNS_ON, timeout = null)) }
        harness.awaitRunning(EXEC_RUNS_ON)
        running.cancel()
        val failure = running.failure("cancelled run")
        expectThat(failure is CancellationException, "Cancelling must not become <$failure>")
        expectThat(!harness.isRunning(EXEC_RUNS_ON), "Cancelling must end the process")
    }
    case("run fails with Disconnected when the connection ends") {
        val harness = newHarness()
        val running = start { harness.exec.run(ExecRequest(EXEC_RUNS_ON, timeout = null)) }
        harness.awaitRunning(EXEC_RUNS_ON)
        harness.disconnect()
        val failure = running.failure("run")
        expectThat(failure is SshException.Disconnected, "Run failed with <$failure>")
    }
    case("a command after the connection ended fails with Disconnected") {
        val harness = newHarness()
        harness.disconnect()
        expectThrows<SshException.Disconnected>("run") { harness.exec.run(ExecRequest(EXEC_CAT)) }
        expectThrows<SshException.Disconnected>("stream") { harness.exec.stream(ExecRequest(EXEC_CAT)).collect {} }
    }
    case("stream delivers the output, and the last event is Exited") {
        val events = newHarness().exec.stream(ExecRequest(EXEC_PRINTS_AND_FAILS)).toList()
        expectEqual("out", collectOutput(events, ::stdoutOf), "stdout")
        expectEqual("err", collectOutput(events, ::stderrOf), "stderr")
        expectEqual<ExecEvent>(ExecEvent.Exited(3, null), events.last(), "last event")
        expectEqual(1, events.count { it is ExecEvent.Exited }, "Exited events")
    }
    case("a stream does nothing until it is collected, and each collection starts the process once") {
        val harness = newHarness()
        val flow = harness.exec.stream(ExecRequest(EXEC_FIVE_EACH))
        expectEqual(0, harness.timesStarted(EXEC_FIVE_EACH), "before collection")
        flow.toList()
        expectEqual(1, harness.timesStarted(EXEC_FIVE_EACH), "after one collection")
        flow.toList()
        expectEqual(2, harness.timesStarted(EXEC_FIVE_EACH), "after two collections")
    }
    case("output arrives while the process runs") {
        val harness = newHarness()
        val seen = collect(harness.exec.stream(ExecRequest(EXEC_RUNS_ON, timeout = null)))
        var output = ""
        while (output != "ready") {
            val event = seen.next("output")
            expectThat(event !is ExecEvent.Exited, "The process must still run")
            output += stdoutOf(event)?.decodeToString().orEmpty()
            expectThat("ready".startsWith(output), "Unexpected output <$output>")
        }
        expectThat(harness.isRunning(EXEC_RUNS_ON), "The process must still run")
        seen.cancel()
    }
    case("stopping collection ends the process") {
        val harness = newHarness()
        harness.exec.stream(ExecRequest(EXEC_RUNS_ON, timeout = null)).first()
        expectThat(!harness.isRunning(EXEC_RUNS_ON), "Stopping collection must end the process")
    }
    case("a stream's timeout comes from collection and ends the process") {
        val harness = newHarness()
        expectThrows<SshException.Timeout>("stream") {
            harness.exec.stream(ExecRequest(EXEC_RUNS_ON, timeout = SHORT_TIMEOUT)).collect {}
        }
        expectThat(!harness.isRunning(EXEC_RUNS_ON), "The timeout must end the process")
    }
    case("a stream fails with Disconnected when the connection ends") {
        val harness = newHarness()
        val seen = collect(harness.exec.stream(ExecRequest(EXEC_RUNS_ON, timeout = null)))
        harness.awaitRunning(EXEC_RUNS_ON)
        harness.disconnect()
        val failure = seen.failure("stream")
        expectThat(failure is SshException.Disconnected, "Stream failed with <$failure>")
    }
}

interface SshSessionHarness {
    val session: SshSession

    /** A folder that exists on the server. */
    val sftpRoot: String

    /** A folder that does not exist on the server. */
    val missingRoot: String

    /** An address a tunnel can reach, as seen from the server. */
    val tunnelAddress: InetSocketAddress

    /** Waits until [command] runs on the server. */
    suspend fun awaitRunning(command: String)

    /** Whether a process of this command runs on the server. */
    suspend fun isRunning(command: String): Boolean

    /** Cuts the connection without closing the session. */
    suspend fun loseConnection()

    /** How many tunnels the server opened, each with one `direct-tcpip` channel. */
    suspend fun tunnelsOpened(): Int
}

/** The promises on [SshSession]. */
fun sshSessionContract(newHarness: suspend () -> SshSessionHarness): List<ContractCase> = contractCases {
    case("close can be called twice and leaves the session disconnected") {
        val session = newHarness().session
        expectThat(session.connected.value, "A new session is connected")
        session.close()
        session.close()
        expectThat(!session.connected.value, "A closed session is not connected")
    }
    case("connected turns false when the connection ends for any reason") {
        val harness = newHarness()
        harness.loseConnection()
        expectThat(!harness.session.connected.value, "A lost session is not connected")
        harness.session.close()
    }
    case("close ends every process of the session") {
        val harness = newHarness()
        val running = start { harness.session.exec.run(ExecRequest(EXEC_RUNS_ON, timeout = null)) }
        harness.awaitRunning(EXEC_RUNS_ON)
        harness.session.close()
        running.result("run")
        expectThat(!harness.isRunning(EXEC_RUNS_ON), "Close must end the process")
    }
    case("close ends every SFTP client of the session") {
        val harness = newHarness()
        val client = harness.session.openSftp(harness.sftpRoot)
        harness.session.close()
        expectThrows<SshException.Disconnected>("list") { client.list("") }
    }
    case("openSftp of a missing folder fails with Sftp") {
        val harness = newHarness()
        expectThrows<SshException.Sftp>("openSftp") { harness.session.openSftp(harness.missingRoot) }
    }
    case("openSftp fails with Disconnected once the connection ended") {
        val harness = newHarness()
        harness.loseConnection()
        expectThrows<SshException.Disconnected>("openSftp") { harness.session.openSftp(harness.sftpRoot) }
    }
    case("each socket opens one tunnel on connect, not before") {
        val harness = newHarness()
        val factory = harness.session.socketFactory()
        val first = factory.createSocket()
        val second = factory.createSocket()
        expectEqual(0, harness.tunnelsOpened(), "tunnels before connect")
        first.connect(harness.tunnelAddress)
        expectEqual(1, harness.tunnelsOpened(), "tunnels after one connect")
        second.connect(harness.tunnelAddress)
        expectEqual(2, harness.tunnelsOpened(), "tunnels after two connects")
        expectThat(first.isConnected && second.isConnected, "Sockets must be connected")
        harness.session.close()
    }
    case("all sockets close when the session closes, including ones in use") {
        val harness = newHarness()
        val factory = harness.session.socketFactory()
        val connected = factory.createSocket().apply { connect(harness.tunnelAddress) }
        val unconnected = factory.createSocket()
        harness.session.close()
        expectThat(connected.isClosed, "A connected socket must close with the session")
        expectThat(unconnected.isClosed, "An unconnected socket must close with the session")
    }
}
