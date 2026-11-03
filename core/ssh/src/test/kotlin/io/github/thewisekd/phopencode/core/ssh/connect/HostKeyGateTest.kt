package io.github.thewisekd.phopencode.core.ssh.connect

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HostKeyGateTest {
    private val serverKey = TestKeys.ed25519().public
    private val otherKey = TestKeys.ed25519().public

    private fun verifyOnAnotherThread(gate: HostKeyGate, key: java.security.PublicKey): CompletableFuture<Boolean> =
        CompletableFuture.supplyAsync { gate.verify("ignored", 22, key) }

    @Test
    fun `the presented key is the SSH wire form`() {
        assertEquals(TestKeys.hostKeyOf(serverKey), HostKeyGate.hostKeyOf(serverKey))
        val rsa = TestKeys.rsa().public
        assertEquals(TestKeys.hostKeyOf(rsa), HostKeyGate.hostKeyOf(rsa))
    }

    @Test
    fun `verify waits for the decision and then accepts`() = runBlocking {
        val gate = HostKeyGate()
        val verdict = verifyOnAnotherThread(gate, serverKey)
        assertEquals(TestKeys.hostKeyOf(serverKey), gate.presented.await())
        assertFalse(verdict.isDone, "verify must wait for the decision")
        gate.accept(TestKeys.hostKeyOf(serverKey))
        assertTrue(verdict.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `reject releases a waiting verify with false`() = runBlocking {
        val gate = HostKeyGate()
        val verdict = verifyOnAnotherThread(gate, serverKey)
        gate.presented.await()
        gate.reject()
        assertFalse(verdict.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a re-key must present the accepted key`() = runBlocking {
        val gate = HostKeyGate()
        val verdict = verifyOnAnotherThread(gate, serverKey)
        gate.presented.await()
        gate.accept(TestKeys.hostKeyOf(serverKey))
        assertTrue(verdict.get(5, TimeUnit.SECONDS))
        assertTrue(gate.verify("ignored", 22, serverKey), "The same key again")
        assertFalse(gate.verify("ignored", 22, otherKey), "A changed key on re-key")
    }
}
