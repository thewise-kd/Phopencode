package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.remote.detect.ServerLookup
import io.github.thewisekd.phopencode.core.remote.notify.NotifyInstallException
import io.github.thewisekd.phopencode.core.remote.notify.NotifyInstallFailure
import io.github.thewisekd.phopencode.core.remote.notify.OpencodeAccessSource
import io.github.thewisekd.phopencode.core.remote.detect.ServerSource
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshSession
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The connection keeps where `opencode serve` is and the [SecretRef] of the password it accepted,
 * never the password itself; [BoundConnection.readOpencodePassword] reads it again through the ref.
 */
class OpencodeLoginTest {
    private val started = "test-" + "started"

    private suspend fun TestScope.connect(fx: Fixture, profile: io.github.thewisekd.phopencode.core.model.ServerProfile = profile()) {
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    @Test
    fun `the resolver pairs each candidate password with its ref, in the order it tries them`() = runTest {
        val secrets = FakeSecretStore()
        val tools = FakeServerTools()
        val resolver = ServerResolver(secrets, tools)
        val startedRef = ServerResolver.startedPasswordRef(profile())
        SecretValue.ofText(OPENCODE_PASSWORD).use { secrets.put(OPENCODE_REF, it) }
        SecretValue.ofText(started).use { secrets.put(startedRef, it) }

        resolver.resolve(FakeSshSession(), profile()).use { assertEquals(listOf(OPENCODE_REF, startedRef), it.passwordRefs) }
        tools.server = ServerLookup.Running("127.0.0.1", 4100, 200, ServerSource.StateFile)
        resolver.resolve(FakeSshSession(), profile()).use { assertEquals(listOf(startedRef, OPENCODE_REF), it.passwordRefs) }
        tools.server = ServerLookup.NotRunning()
        resolver.resolve(FakeSshSession(), profile()).use { assertEquals(listOf(startedRef), it.passwordRefs) }
    }

    @Test
    fun `a connection keeps the base URL and the ref of the profile's credential the server accepted`() = runTest {
        val fx = Fixture(this)
        connect(fx)

        val login = assertNotNull(fx.manager.withConnection { it.opencode })

        assertEquals("http://127.0.0.1:4096", login.baseUrl)
        assertEquals(OPENCODE_REF, login.passwordRef)
        fx.manager.withConnection { bound ->
            bound.readOpencodePassword()!!.use { assertEquals(OPENCODE_PASSWORD, it.revealText()) }
        }
    }

    @Test
    fun `a connection to a server it started keeps the started password's ref`() = runTest {
        val fx = Fixture(this)
        fx.tools.server = ServerLookup.NotRunning()
        connect(fx)

        val login = assertNotNull(fx.manager.withConnection { it.opencode })

        assertEquals("http://127.0.0.1:4100", login.baseUrl)
        assertEquals(ServerResolver.startedPasswordRef(profile()), login.passwordRef)
        val password = fx.manager.withConnection { it.readOpencodePassword() }!!
        password.use { assertEquals(fx.tools.startedWith.single().second, it.revealText()) }
    }

    @Test
    fun `when the profile's credential is refused, the ref of the started password that worked is kept`() = runTest {
        val fx = Fixture(this)
        fx.clients.serverPassword = { started }
        SecretValue.ofText(started).use { fx.secrets.put(ServerResolver.startedPasswordRef(profile()), it) }
        connect(fx)

        assertEquals(ServerResolver.startedPasswordRef(profile()), fx.manager.withConnection { it.opencode }?.passwordRef)
    }

    @Test
    fun `a secret deleted since the connect reads as null, and an unreadable one too`() = runTest {
        val fx = Fixture(this)
        connect(fx)

        fx.secrets.delete(OPENCODE_REF)
        assertNull(fx.manager.withConnection { it.readOpencodePassword() })
        fx.secrets.seedUnreadable(OPENCODE_REF)
        assertNull(fx.manager.withConnection { it.readOpencodePassword() })
    }

    @Test
    fun `an unchanged secret passes the check against the accepted password`() = runTest {
        val fx = Fixture(this)
        connect(fx)

        repeat(2) {
            fx.manager.withConnection { it.readOpencodePassword() }!!.use { assertEquals(OPENCODE_PASSWORD, it.revealText()) }
        }
    }

    @Test
    fun `a secret changed since the connect is never handed out, and no install step runs`() = runTest {
        val fx = Fixture(this)
        connect(fx)
        SecretValue.ofText("test-" + "changed").use { fx.secrets.put(OPENCODE_REF, it) }

        assertNull(fx.manager.withConnection { it.readOpencodePassword() })
        val failure = assertFailsWith<NotifyInstallException> {
            fx.manager.withConnection { bound -> OpencodeAccessSource.FromConnection.access(bound) }
        }
        assertEquals(NotifyInstallFailure.AccessUnavailable, failure.failure)
        assertEquals(emptyList(), fx.connector.sessions.single().exec.executions.map { it.command }, "nothing ran on the server")
    }

    @Test
    fun `the password check prints neither its key nor its digest`() {
        val check = SecretValue.ofText(OPENCODE_PASSWORD).use(PasswordCheck::of)
        val other = SecretValue.ofText(OPENCODE_PASSWORD).use(PasswordCheck::of)

        assertEquals("PasswordCheck(***)", check.toString())
        assertEquals(check.toString(), other.toString())
        SecretValue.ofText(OPENCODE_PASSWORD).use { assertTrue(check.matches(it)) }
        SecretValue.ofText("test-" + "changed").use { assertFalse(check.matches(it)) }
        assertFalse(check == other, "each connection has its own key")
    }

    @Test
    fun `without a connection nothing is read`() = runTest {
        val fx = Fixture(this)
        fx.seedSecrets()

        assertFailsWith<NotConnectedException> { fx.manager.withConnection { it.readOpencodePassword() } }
    }

    @Test
    fun `nothing prints a secret or the address`() = runTest {
        val fx = Fixture(this)
        connect(fx)
        val login = fx.manager.withConnection { it.opencode }!!
        val texts = listOf(login.toString(), fx.manager.state.value.toString(), fx.manager.withConnection { it.toString() })

        texts.forEach { text ->
            assertFalse(OPENCODE_PASSWORD in text, text)
            assertFalse(OPENCODE_REF.toString() in text && "opencode-login" in text, text)
            assertFalse("127.0.0.1" in text, text)
        }
        assertEquals("OpencodeLogin(***)", login.toString())
        ResolvedServer("http://127.0.0.1:1", listOf(SecretValue.ofText(OPENCODE_PASSWORD)), listOf(OPENCODE_REF)).use {
            assertEquals("ResolvedServer(***)", it.toString())
        }
    }
}
