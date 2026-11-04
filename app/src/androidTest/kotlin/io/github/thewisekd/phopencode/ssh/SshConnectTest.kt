package io.github.thewisekd.phopencode.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** An ed25519 login from the emulator to the runner's sshd, through the app's BouncyCastle. */
@RunWith(AndroidJUnit4::class)
class SshConnectTest {
    private lateinit var server: SshTestServer

    @Before
    fun setUp() {
        server = SshTestServer.fromArguments()
    }

    @Test
    fun ed25519KeyLogsInWhenTheHostKeyMatches() = runBlocking<Unit> {
        val seen = CopyOnWriteArrayList<HostKey>()

        val session = server.connect(server.verifier(seen = seen))
        session.use {
            assertTrue(it.connected.value)
            assertEquals(1, seen.size)
            assertEquals("ssh-ed25519", seen.single().type)
            assertEquals(server.hostKeyFingerprint, seen.single().fingerprintSha256)
        }

        assertFalse(session.connected.value)
    }

    @Test
    fun anotherHostKeyIsRejected() {
        val other = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32))
        assertNotEquals(server.hostKeyFingerprint, other)

        val failure = assertThrows(SshException.HostKeyRejected::class.java) {
            runBlocking { server.connect(server.verifier(expected = other)).close() }
        }

        assertEquals("ssh-ed25519", failure.hostKey.type)
        assertEquals(server.hostKeyFingerprint, failure.hostKey.fingerprintSha256)
    }
}
