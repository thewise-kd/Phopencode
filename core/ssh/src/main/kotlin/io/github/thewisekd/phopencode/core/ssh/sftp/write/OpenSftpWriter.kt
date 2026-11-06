package io.github.thewisekd.phopencode.core.ssh.sftp.write

import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.connect.RedactedFailure
import io.github.thewisekd.phopencode.core.ssh.connect.SshjSession
import io.github.thewisekd.phopencode.core.ssh.sftp.SftpRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.SFTPClient

/**
 * Opens an SFTP channel on [session] and a [SftpWriter] over it, confined to [root]. The session
 * tracks the channel, so closing the session closes it; closing the writer closes the channel and
 * leaves the session open.
 *
 * @throws SshException.Disconnected when the session is closed or lost.
 */
internal suspend fun openSftpWriter(
    session: SshjSession,
    root: String,
    hooks: WriteHooks = WriteHooks(),
    beforeHandOver: (SftpWriter) -> Unit = {},
): SftpWriter {
    // A malformed root fails here, before the channel opens.
    SftpRoot.of(root)
    session.requireConnected()
    // Opening is bounded by sshj's timeouts and runs to the end. withContext hands its result back
    // only if the caller is still active, else it drops it; so the writer is kept where the catch
    // below sees it from the moment it exists, and closed there when the caller is gone.
    var opened: SftpWriter? = null
    try {
        withContext(NonCancellable + Dispatchers.IO) {
            val sftp: SFTPClient = try {
                session.client.newSFTPClient()
            } catch (e: Exception) {
                // A lost session says Disconnected; a server without SFTP says Failed.
                session.requireConnected()
                throw SshException.Sftp(SftpFailure.Failed, RedactedFailure.of(e))
            }
            if (!session.track(sftp)) throw SshException.Disconnected()
            // Closed off the caller's thread; see SshjSession.release.
            val release = { session.release(sftp) }
            val writer = try {
                SftpWriter.open(sftp, root, release, hooks)
            } catch (e: Throwable) {
                release()
                throw e
            }
            opened = writer
            beforeHandOver(writer)
        }
        currentCoroutineContext().ensureActive()
        return checkNotNull(opened)
    } catch (e: Throwable) {
        opened?.close()
        throw e
    }
}
