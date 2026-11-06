package io.github.thewisekd.phopencode.feature.git.logic.contract

import io.github.thewisekd.phopencode.core.remote.connection.NotConnectedException
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class GitFailureTest {
    @Test
    fun `ssh failures map to the cases the screens word`() {
        assertEquals(GitFailure.Unreachable, GitFailure.of(SshException.ConnectionFailed()))
        assertEquals(GitFailure.TimedOut, GitFailure.of(SshException.Timeout()))
        assertEquals(GitFailure.NotConnected, GitFailure.of(SshException.Disconnected()))
        assertEquals(GitFailure.Unauthorized, GitFailure.of(SshException.AuthenticationFailed()))
        assertEquals(GitFailure.Unauthorized, GitFailure.of(SshException.HostKeyRejected(HostKey("ssh-ed25519", ByteArray(32)))))
        assertEquals(GitFailure.OutputTooLarge, GitFailure.of(SshException.OutputLimitExceeded(1024)))
        assertEquals(GitFailure.Unknown, GitFailure.of(SshException.Sftp(SftpFailure.Failed)))
    }

    @Test
    fun `a git error keeps its exit code`() {
        assertEquals(128, GitFailure.GitError(128).exitCode)
        assertNull(GitFailure.GitError(null).exitCode)
        assertEquals("GitError(exitCode=1)", GitFailure.GitError(1).toString())
    }

    @Test
    fun `failures thrown at the ports become git exceptions`() {
        val own = GitException(GitFailure.Conflict)
        assertSame(own, own.toGitException())
        assertEquals(GitFailure.NotConnected, NotConnectedException().toGitException().failure)
        assertEquals(GitFailure.TimedOut, SshException.Timeout().toGitException().failure)
        assertEquals(GitFailure.Unknown, IllegalArgumentException("x").toGitException().failure)
        assertNull(IllegalArgumentException("x").toGitException().cause)
    }

    @Test
    fun `the message names the failure only`() {
        val secretPath = listOf("/srv", "gizli", "token.env").joinToString("/")
        val error = GitException(GitFailure.NotRepository, IllegalStateException(secretPath))

        assertEquals("Git operation failed: NotRepository", error.message)
        assertFalse(secretPath in error.toString())
    }
}
