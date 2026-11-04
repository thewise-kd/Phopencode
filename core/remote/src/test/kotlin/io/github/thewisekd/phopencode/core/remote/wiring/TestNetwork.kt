package io.github.thewisekd.phopencode.core.remote.wiring

import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.encoding.Base64
import org.apache.sshd.common.config.keys.PublicKeyEntry

internal val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

/** Test values only. Nothing here is a real secret. */
internal object TestSecrets {
    const val USER = "test-user"
    const val PASSWORD = "test-password"
    const val WRONG_PASSWORD = "fake-password"
}

internal fun password(value: String = TestSecrets.PASSWORD) = SshCredential.Password(value.toCharArray())

/** Keys generated at runtime, so that no key lives in the repository. */
internal object TestKeys {
    /** ECDSA P-256, which the JDK provides without BouncyCastle on this classpath. */
    fun hostKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    /** The key as the API sees it, computed by MINA, independently of the code under test. */
    fun hostKeyOf(key: PublicKey): HostKey {
        val (type, base64) = PublicKeyEntry.toString(key).split(' ')
        return HostKey(type, Base64.decode(base64))
    }
}

/** A loopback port nothing listens on. */
internal fun unusedPort(): Int = ServerSocket(0, 1, LOOPBACK).use { it.localPort }

/** Accepts on loopback and never sends a byte. Counts connections the client has not closed. */
internal class SilentServer : AutoCloseable {
    private val server = ServerSocket(0, 50, LOOPBACK)
    private val accepted = CopyOnWriteArrayList<Socket>()
    private val open = AtomicInteger()

    private val acceptor = thread(isDaemon = true, name = "wiring-test-silent") {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            accepted += socket
            open.incrementAndGet()
            thread(isDaemon = true, name = "wiring-test-silent-reader") {
                runCatching { socket.getInputStream().use { input -> while (input.read() >= 0) Unit } }
                open.decrementAndGet()
            }
        }
    }

    val target: SshTarget = SshTarget("127.0.0.1", server.localPort, TestSecrets.USER)

    fun openConnections(): Int = open.get()

    override fun close() {
        server.close()
        accepted.forEach { runCatching { it.close() } }
        acceptor.join(1_000)
    }
}

/** A loopback TCP echo server. */
internal class EchoServer : AutoCloseable {
    private val server = ServerSocket(0, 50, LOOPBACK)
    private val accepted = CopyOnWriteArrayList<Socket>()

    private val acceptor = thread(isDaemon = true, name = "wiring-test-echo") {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            accepted += socket
            thread(isDaemon = true, name = "wiring-test-echo-worker") {
                runCatching { socket.getInputStream().copyTo(socket.getOutputStream()) }
                runCatching { socket.close() }
            }
        }
    }

    val port: Int get() = server.localPort

    override fun close() {
        server.close()
        accepted.forEach { runCatching { it.close() } }
        acceptor.join(1_000)
    }
}

/** Processes on this machine, which is the test server's "remote" side. */
internal object LocalProcesses {
    /** Live processes whose process group is one of [groups], read from /proc. */
    fun inGroups(groups: Set<Long>): List<Long> {
        if (groups.isEmpty()) return emptyList()
        val entries = Files.list(Path.of("/proc")).use { it.toList() }
        return entries.mapNotNull { it.fileName.toString().toLongOrNull() }.filter { pid ->
            val text = runCatching { Files.readString(Path.of("/proc/$pid/stat")) }.getOrNull() ?: return@filter false
            // After the name, which is in parentheses and may hold spaces: state, parent, group.
            val fields = text.substringAfterLast(')').trim().split(' ')
            val state = fields.getOrNull(0)?.firstOrNull()
            state != 'Z' && state != 'X' && fields.getOrNull(2)?.toLongOrNull() in groups
        }
    }
}
