package io.github.thewisekd.phopencode.core.ssh.connect

import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.ExecResult
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.exec.ExecFeature
import io.github.thewisekd.phopencode.core.ssh.sftp.SshjSftpClient
import io.github.thewisekd.phopencode.core.ssh.tunnel.TunnelSocketFactory
import javax.net.SocketFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * What a logged-in [SshjSession] offers beyond the connection itself. Commands, SFTP and tunnels
 * come from their own packages; they reach the connection through [SshjSession.client] and hand
 * every channel they open to [SshjSession.track], so that closing the session closes it.
 */
internal interface SessionFeatures {
    fun exec(session: SshjSession): RemoteExec

    suspend fun openSftp(session: SshjSession, root: String): SftpClient

    fun socketFactory(session: SshjSession): SocketFactory
}

/**
 * What the public [SshjConnector] constructor uses: commands from [ExecFeature], one
 * [SshjSftpClient] (read and write over one SFTP channel) per [openSftp], and a
 * [TunnelSocketFactory] per [socketFactory].
 */
internal object SshjSessionFeatures : SessionFeatures {
    override fun exec(session: SshjSession): RemoteExec = ExecFeature.exec(session)

    override suspend fun openSftp(session: SshjSession, root: String): SftpClient = SshjSftpClient.open(session, root)

    override fun socketFactory(session: SshjSession): SocketFactory = TunnelSocketFactory(session)
}

/** For tests that plug in one feature at a time. Every other use fails loudly. */
internal object NoSessionFeatures : SessionFeatures {
    override fun exec(session: SshjSession): RemoteExec = Unavailable

    override suspend fun openSftp(session: SshjSession, root: String): SftpClient {
        session.requireConnected()
        throw UnsupportedOperationException("SFTP is not available yet")
    }

    override fun socketFactory(session: SshjSession): SocketFactory =
        throw UnsupportedOperationException("Tunnels are not available yet")

    private object Unavailable : RemoteExec {
        override suspend fun run(request: ExecRequest, maxOutputBytes: Long): ExecResult =
            throw UnsupportedOperationException("Remote commands are not available yet")

        override fun stream(request: ExecRequest): Flow<ExecEvent> = flow {
            throw UnsupportedOperationException("Remote commands are not available yet")
        }
    }
}
