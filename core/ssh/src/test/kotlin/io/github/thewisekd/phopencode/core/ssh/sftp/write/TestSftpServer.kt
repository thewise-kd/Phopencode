package io.github.thewisekd.phopencode.core.ssh.sftp.write

import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.ssh.connect.TestKeys
import io.github.thewisekd.phopencode.core.ssh.connect.TestSecrets
import java.io.IOException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.SftpModuleProperties
import org.apache.sshd.sftp.server.Handle
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory

/**
 * An embedded MINA server on 127.0.0.1 with an SFTP subsystem whose `/` is [home], a fresh local
 * temporary folder. It counts every SFTP request and records the extensions used.
 *
 * @param openSshExtensions Overrides the OpenSSH extensions the server announces, such as
 *   `statvfs@openssh.com=2` for a server without `posix-rename` and `fsync`.
 * @param vetoMove Makes a rename fail when it returns true for the local source and target.
 */
internal class TestSftpServer(
    openSshExtensions: String? = null,
    private val vetoMove: (Path, Path) -> Boolean = { _, _ -> false },
) : AutoCloseable {
    val home: Path = Files.createTempDirectory("phopencode-sftp-write").toRealPath()

    private val requests = AtomicInteger()
    private val handles = AtomicInteger()
    private val subsystems = AtomicInteger()
    val extensions: MutableList<String> = CopyOnWriteArrayList()

    private val listener = object : SftpEventListener {
        override fun received(session: ServerSession, type: Int, id: Int) {
            requests.incrementAndGet()
        }

        override fun initialized(session: ServerSession, version: Int) {
            subsystems.incrementAndGet()
        }

        override fun destroying(session: ServerSession) {
            subsystems.decrementAndGet()
        }

        override fun open(session: ServerSession, remoteHandle: String, localHandle: Handle) {
            handles.incrementAndGet()
        }

        override fun closed(session: ServerSession, remoteHandle: String, localHandle: Handle, thrown: Throwable?) {
            handles.decrementAndGet()
        }

        override fun receivedExtension(session: ServerSession, extension: String, id: Int) {
            extensions += extension
        }

        override fun moving(session: ServerSession, srcPath: Path, dstPath: Path, opts: Collection<CopyOption>) {
            if (vetoMove(srcPath, dstPath)) throw IOException("vetoed by the test")
        }
    }

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = KeyPairProvider.wrap(TestKeys.ed25519())
        passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
            user == TestSecrets.USER && password == TestSecrets.PASSWORD
        }
        keyboardInteractiveAuthenticator = null
        fileSystemFactory = VirtualFileSystemFactory(home)
        subsystemFactories = listOf(SftpSubsystemFactory().apply { addSftpEventListener(listener) })
        if (openSshExtensions != null) SftpModuleProperties.OPENSSH_EXTENSIONS.set(this, openSshExtensions)
        start()
    }

    val target: SshTarget get() = SshTarget("127.0.0.1", server.port, TestSecrets.USER)

    /** SFTP requests received so far, on every session. */
    fun requestCount(): Int = requests.get()

    /** File and folder handles open now, on every session. */
    fun openHandles(): Int = handles.get()

    /** SFTP channels open now, on every session. */
    fun openSubsystems(): Int = subsystems.get()

    @OptIn(ExperimentalPathApi::class)
    override fun close() {
        server.stop(true)
        home.deleteRecursively()
    }
}
