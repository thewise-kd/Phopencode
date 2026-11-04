package io.github.thewisekd.phopencode.core.ssh.sftp.read

import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.ssh.connect.TestKeys
import io.github.thewisekd.phopencode.core.ssh.connect.TestSecrets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory

/**
 * An embedded MINA server with the SFTP subsystem over the real file system, on 127.0.0.1 and a
 * random port. It counts the SFTP requests it receives and the file bytes it reads.
 */
internal class SftpReadServer : AutoCloseable {
    private val requests = AtomicInteger()
    private val requestsByType = ConcurrentHashMap<Int, AtomicInteger>()
    private val bytesRead = AtomicLong()

    /** Runs on the server's thread for every SFTP request, before the server answers it. */
    @Volatile
    var onRequest: (type: Int) -> Unit = {}

    private val sftp = SftpSubsystemFactory().apply {
        addSftpEventListener(object : SftpEventListener {
            override fun received(session: ServerSession, type: Int, id: Int) {
                requests.incrementAndGet()
                requestsByType.computeIfAbsent(type) { AtomicInteger() }.incrementAndGet()
                onRequest(type)
            }

            override fun read(
                session: ServerSession,
                remoteHandle: String,
                localHandle: FileHandle,
                offset: Long,
                data: ByteArray,
                dataOffset: Int,
                dataLen: Int,
                readLen: Int,
                thrown: Throwable?,
            ) {
                if (readLen > 0) bytesRead.addAndGet(readLen.toLong())
            }
        })
    }

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = KeyPairProvider.wrap(TestKeys.ed25519())
        passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
            user == TestSecrets.USER && password == TestSecrets.PASSWORD
        }
        publickeyAuthenticator = null
        keyboardInteractiveAuthenticator = null
        fileSystemFactory = NativeFileSystemFactory()
        subsystemFactories = listOf(sftp)
        start()
    }

    val target: SshTarget get() = SshTarget("127.0.0.1", server.port, TestSecrets.USER)

    /** SFTP requests received so far, over every channel. */
    fun requests(): Int = requests.get()

    /** SFTP requests of one packet type, such as `SftpConstants.SSH_FXP_READDIR`. */
    fun requests(type: Int): Int = requestsByType[type]?.get() ?: 0

    /** Handles opened (files and folders) minus handles closed. */
    fun openHandles(): Int =
        requests(SftpConstants.SSH_FXP_OPEN) + requests(SftpConstants.SSH_FXP_OPENDIR) - requests(SftpConstants.SSH_FXP_CLOSE)

    /** File bytes the server has read so far for clients. */
    fun bytesRead(): Long = bytesRead.get()

    fun openSessions(): Int = server.activeSessions.size

    /** Drops every connection from the server's side. */
    fun dropAll() {
        server.activeSessions.toList().forEach { it.close(true) }
    }

    override fun close() {
        server.stop(true)
    }
}
