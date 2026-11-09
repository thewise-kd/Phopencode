package io.github.thewisekd.phopencode.core.remote.notify

import io.github.thewisekd.phopencode.core.api.FsApi
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.remote.connection.BoundConnection
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionServer
import io.github.thewisekd.phopencode.core.remote.connection.OpencodeLogin
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** [OpencodeAccessSource.FromConnection] reads the password through the connection's ref, each time. */
class OpencodeAccessSourceTest {
    private val password = "fake-pw-" + List(10) { ('a'..'z').random() }.joinToString("")

    private class Connection(var login: OpencodeLogin?, var stored: String?) : BoundConnection {
        var reads = 0
        override val exec: RemoteExec get() = error("Not used")
        override val fs: FsApi get() = error("Not used")
        override val server: ConnectionServer get() = error("Not used")
        override val generation: Long = 1
        override val opencode: OpencodeLogin? get() = login

        override suspend fun readOpencodePassword(): SecretValue? {
            reads++
            return stored?.let(SecretValue::ofText)
        }

        override suspend fun openSftp(root: String): SftpClient = error("Not used")
    }

    private val login = OpencodeLogin("http://127.0.0.1:4096", SecretRef("notify-test-ref"))

    @Test
    fun `the access is the login's URL and a fresh read of its password, closed by its owner`() = runTest {
        val connection = Connection(login, password)

        val access = OpencodeAccessSource.FromConnection.access(connection)

        assertEquals("http://127.0.0.1:4096", access.url)
        assertEquals("opencode", access.username)
        assertEquals(password, access.password.revealText())
        assertEquals("OpencodeAccess(***)", access.toString())
        assertFalse(password in access.toString())
        access.close()
        assertFailsWith<IllegalStateException> { access.password.revealText() }
        OpencodeAccessSource.FromConnection.access(connection).close()
        assertEquals(2, connection.reads, "read again for every access")
    }

    @Test
    fun `no login or no readable secret is an unavailable access`() = runTest {
        val gone = Connection(login, stored = null)
        val noLogin = Connection(login = null, stored = password)

        listOf(gone, noLogin).forEach { connection ->
            val failure = assertFailsWith<NotifyInstallException> { OpencodeAccessSource.FromConnection.access(connection) }
            assertEquals(NotifyInstallFailure.AccessUnavailable, failure.failure)
            assertFalse(password in failure.toString())
            assertNull(failure.cause)
        }
        assertEquals(0, noLogin.reads)
    }

    @Test
    fun `a default BoundConnection knows no login and reads nothing`() = runTest {
        val plain = object : BoundConnection {
            override val exec: RemoteExec get() = error("Not used")
            override val fs: FsApi get() = error("Not used")
            override val server: ConnectionServer get() = error("Not used")
            override val generation: Long = 1
            override suspend fun openSftp(root: String): SftpClient = error("Not used")
        }

        assertNull(plain.opencode)
        assertNull(plain.readOpencodePassword())
    }
}
