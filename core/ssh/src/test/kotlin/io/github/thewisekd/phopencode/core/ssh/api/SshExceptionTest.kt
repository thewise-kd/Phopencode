package io.github.thewisekd.phopencode.core.ssh.api

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

class SshExceptionTest {
    private val hostKey = HostKey("ssh-ed25519", byteArrayOf(1, 2, 3))

    private fun all(cause: Throwable?): List<SshException> = listOf(
        SshException.ConnectionFailed(cause),
        SshException.Disconnected(cause),
        SshException.AuthenticationFailed(cause),
        SshException.HostKeyRejected(hostKey, cause),
        SshException.Timeout(cause),
        SshException.OutputLimitExceeded(1024, cause),
    ) + SftpFailure.entries.map { SshException.Sftp(it, cause) }

    @Test
    fun `no failure takes free text, so none can carry a secret`() {
        all(null).forEach { failure ->
            assertTrue(failure.message.orEmpty().isNotBlank(), failure::class.toString())
            val withText = failure::class.java.constructors.filter { String::class.java in it.parameterTypes }
            assertEquals(emptyList(), withText)
        }
    }

    @Test
    fun `a cause stays available without entering the message`() {
        val cause = IOException(listOf("sentinel", "value").joinToString("-"))
        all(cause).forEach { failure ->
            assertSame(cause, failure.cause)
            assertFalse("sentinel" in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun `a failure never looks like cancellation`() {
        all(null).forEach { failure ->
            val thrown: Throwable = failure
            assertFalse(thrown is CancellationException)
        }
    }

    @Test
    fun `a rejected key is kept for the approval dialog`() {
        assertEquals(hostKey, SshException.HostKeyRejected(hostKey).hostKey)
    }
}
