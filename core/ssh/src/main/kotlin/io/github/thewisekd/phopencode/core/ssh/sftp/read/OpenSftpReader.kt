package io.github.thewisekd.phopencode.core.ssh.sftp.read

import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.connect.RedactedFailure
import io.github.thewisekd.phopencode.core.ssh.connect.SshjSession
import io.github.thewisekd.phopencode.core.ssh.sftp.SftpRoot
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.SFTPClient

/**
 * Opens an SFTP channel on [session] and a [SftpReader] over it, confined to [root].
 *
 * A malformed [root] fails with [SftpFailure.PathRejected] before the channel opens. The channel is
 * handed to [SshjSession.track], so it closes with the session; closing the reader closes it
 * earlier. A failure or cancellation while opening leaves no channel behind.
 *
 * @throws SshException.Disconnected when the session is closed or lost.
 */
internal suspend fun openSftpReader(session: SshjSession, root: String): SftpReader {
    val normalised = SftpRoot.of(root).path
    session.requireConnected()
    val connected = { session.connected.value && session.client.isConnected }
    val opened = AtomicReference<SFTPClient?>()
    try {
        val sftp = withContext(Dispatchers.IO) {
            val sftp = try {
                session.client.newSFTPClient()
            } catch (failure: IOException) {
                val cause = RedactedFailure.of(failure)
                throw if (connected() && !transportFailed(failure)) {
                    SshException.Sftp(SftpFailure.Failed, cause)
                } else {
                    SshException.Disconnected(cause)
                }
            }
            opened.set(sftp)
            if (!session.track(sftp)) throw SshException.Disconnected()
            sftp
        }
        return SftpReader(sftp, normalised, connected) {
            session.untrack(sftp)
            runCatching { sftp.close() }
        }
    } catch (failure: Throwable) {
        opened.getAndSet(null)?.let { sftp ->
            session.untrack(sftp)
            runCatching { sftp.close() }
        }
        throw failure
    }
}
