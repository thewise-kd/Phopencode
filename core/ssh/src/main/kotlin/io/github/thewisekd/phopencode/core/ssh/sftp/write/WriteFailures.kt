package io.github.thewisekd.phopencode.core.ssh.sftp.write

import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.connect.RedactedFailure
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPEngine
import net.schmizz.sshj.sftp.SFTPException

/**
 * [failure] as this module's exception. A server status becomes its [SftpFailure]; a closed channel
 * or writer becomes [SshException.Disconnected]. The library exception travels only redacted,
 * because its message can quote a path.
 */
internal fun failureOf(engine: SFTPEngine, failure: Exception, closed: () -> Boolean): Exception {
    if (failure is SshException || failure is CancellationException) return failure
    val cause = RedactedFailure.of(failure)
    val status = (failure as? SFTPException)?.statusCode
    if (status != null && status != StatusCode.UNKNOWN) {
        return when (status) {
            StatusCode.NO_CONNECTION, StatusCode.CONNECITON_LOST -> SshException.Disconnected(cause)
            else -> SshException.Sftp(failureOf(status), cause)
        }
    }
    if (closed() || !engine.subsystem.isOpen) return SshException.Disconnected(cause)
    if (generateSequence<Throwable>(failure) { it.cause }.take(MAX_CAUSES).any { it is TimeoutException }) {
        return SshException.Timeout(cause)
    }
    return SshException.Sftp(SftpFailure.Failed, cause)
}

private fun failureOf(status: StatusCode): SftpFailure = when (status) {
    StatusCode.NO_SUCH_FILE, StatusCode.NO_SUCH_PATH -> SftpFailure.NotFound
    StatusCode.PERMISSION_DENIED, StatusCode.WRITE_PROTECT -> SftpFailure.PermissionDenied
    StatusCode.FILE_ALREADY_EXISTS -> SftpFailure.AlreadyExists
    StatusCode.DIR_NOT_EMPTY -> SftpFailure.NotEmpty
    else -> SftpFailure.Failed
}

private const val MAX_CAUSES = 8
