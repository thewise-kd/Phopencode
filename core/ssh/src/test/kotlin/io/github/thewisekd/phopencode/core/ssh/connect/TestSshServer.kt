package io.github.thewisekd.phopencode.core.ssh.connect

import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.bouncycastle.jce.provider.BouncyCastleProvider

/** Test values only. Nothing here is a real secret. */
internal object TestSecrets {
    const val USER = "test-user"
    const val PASSWORD = "test-password"
    const val WRONG_PASSWORD = "fake-password"
    const val PASSPHRASE = "test-key-secret"
    const val WRONG_PASSPHRASE = "fake-key-secret"
}

/** Keys generated at runtime, so that no key lives in the repository. */
internal object TestKeys {
    /** BouncyCastle's, because MINA reads Ed25519 keys through BouncyCastle. */
    fun ed25519(): KeyPair = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider()).generateKeyPair()

    fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** The private key in OpenSSH format, encrypted when [passphrase] is given. */
    fun openSsh(keyPair: KeyPair, passphrase: String? = null): String {
        val context = passphrase?.let { OpenSSHKeyEncryptionContext().apply {
                password = it
                cipherName = "AES"
                cipherType = "256"
                cipherMode = "CTR"
            } }
        val out = ByteArrayOutputStream()
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "test", context, out)
        return out.toString(Charsets.UTF_8)
    }

    /** The key as this module's API sees it, computed by MINA, independently of the code under test. */
    fun hostKeyOf(key: PublicKey): HostKey {
        val (type, base64) = PublicKeyEntry.toString(key).split(' ')
        return HostKey(type, Base64.decode(base64))
    }
}

/**
 * An embedded MINA server on 127.0.0.1 and a random port. It accepts [TestSecrets.PASSWORD] and the
 * public keys in [authorizedKeys], and counts every credential it receives.
 */
internal class TestSshServer(
    val hostKeyPair: KeyPair = TestKeys.ed25519(),
    val authorizedKeys: List<PublicKey> = emptyList(),
) : AutoCloseable {
    private val credentials = AtomicInteger()

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = KeyPairProvider.wrap(hostKeyPair)
        passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
            credentials.incrementAndGet()
            user == TestSecrets.USER && password == TestSecrets.PASSWORD
        }
        publickeyAuthenticator = PublickeyAuthenticator { user, key, _ ->
            credentials.incrementAndGet()
            user == TestSecrets.USER && authorizedKeys.any { KeyUtils.compareKeys(it, key) }
        }
        keyboardInteractiveAuthenticator = null
        start()
    }

    val port: Int get() = server.port

    val target: SshTarget get() = SshTarget("127.0.0.1", port, TestSecrets.USER)

    val hostKey: HostKey get() = TestKeys.hostKeyOf(hostKeyPair.public)

    fun credentialsReceived(): Int = credentials.get()

    fun openSessions(): Int = server.activeSessions.size

    /** Drops every connection from the server's side. */
    fun dropAll() {
        server.activeSessions.toList().forEach { it.close(true) }
    }

    override fun close() {
        server.stop(true)
    }
}

internal fun password(value: String = TestSecrets.PASSWORD) = SshCredential.Password(value.toCharArray())

internal fun privateKey(keyPair: KeyPair, passphrase: String? = null) =
    SshCredential.PrivateKey(TestKeys.openSsh(keyPair, passphrase).toCharArray(), passphrase?.toCharArray())
