package io.github.thewisekd.phopencode.feature.preview.logic.transport

import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewException
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout

/** A bridge that cannot start leaves nothing behind: no listening socket, no watchdog thread. */
@Timeout(30)
class BridgeStartFailureTest {
    private fun watchdogThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filter { it.name == WATCHDOG_THREAD && it.isAlive }.toSet()

    @Test
    fun `a bridge whose accept thread cannot start closes what it opened and fails with a typed error`() {
        val before = watchdogThreads()
        var server: ServerSocket? = null
        val factory = LoopbackBridgeFactory(
            limits = BridgeLimits(),
            listen = { ServerSocket(0, 1, LOOPBACK).also { server = it } },
            startThread = { _, _ -> throw OutOfMemoryError("unable to create native thread") },
        )

        val failure = assertFailsWith<PreviewException> { factory.open(TestTunnel(freePort())) }

        assertEquals(PreviewFailure.BridgeFailed, failure.failure)
        val listened = server!!
        assertTrue(listened.isClosed, "The server socket stayed open")
        assertFailsWith<ConnectException> {
            Socket().use { it.connect(InetSocketAddress(LOOPBACK, listened.localPort), 1_000) }
        }
        val started = watchdogThreads() - before
        val deadline = System.currentTimeMillis() + 5_000
        while (started.any { it.isAlive }) {
            check(System.currentTimeMillis() < deadline) { "The watchdog thread kept running" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val WATCHDOG_THREAD = "phopencode-bridge-watchdog"
    }
}
