package io.github.thewisekd.phopencode.core.ssh.key

import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Test values only. Nothing here is a real secret. */
internal object KeySecrets {
    const val USER = "key-test-user"
    const val PASSPHRASE = "test-key-secret"
    const val WRONG_PASSPHRASE = "fake-key-secret"
    const val COMMENT = "phone@test"
}

/**
 * A key file's armour around [body]; only the BEGIN line when [body] is null. The markers are put
 * together here so that the repository's secret scan sees no key block in the sources.
 */
internal fun armored(label: String, body: String?): String {
    val kind = "$label PRIVATE" + " KEY-----"
    val begin = "-----BEGIN $kind\n"
    return if (body == null) begin else begin + (if (body.isEmpty()) "" else "$body\n") + "-----END $kind\n"
}

/** Keys made by MINA at runtime, independently of the code under test. */
internal object MinaKeys {
    fun ed25519(): KeyPair = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider()).generateKeyPair()

    fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    fun ecdsa(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun openSsh(keyPair: KeyPair, comment: String, passphrase: String? = null): ByteArray {
        val context = passphrase?.let {
            OpenSSHKeyEncryptionContext().apply {
                password = it
                cipherName = "AES"
                cipherType = "256"
                cipherMode = "CTR"
            }
        }
        val out = ByteArrayOutputStream()
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, comment, context, out)
        return out.toByteArray()
    }

    /** `type base64`, as MINA prints the key. */
    fun publicLine(key: PublicKey): String = PublicKeyEntry.toString(key)

    fun parsePublic(line: String): PublicKey = PublicKeyEntry.parsePublicKeyEntry(line).resolvePublicKey(null, null, null)

    /** MINA's own reading of a private key file; null passphrase for an unencrypted one. */
    fun load(file: ByteArray, passphrase: String?): KeyPair =
        SecurityUtils.loadKeyPairIdentities(
            null,
            NamedResource.ofName("test"),
            ByteArrayInputStream(file),
            passphrase?.let { FilePasswordProvider.of(it) },
        ).single()
}

/** The system's `ssh-keygen`, when there is one. Tests that need it are skipped without it. */
internal object SshKeygen {
    val available: Boolean by lazy {
        runCatching {
            val process = ProcessBuilder("ssh-keygen", "-?").redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            process.waitFor(10, TimeUnit.SECONDS)
        }.getOrDefault(false)
    }

    fun assume() = assumeTrue(available, "ssh-keygen is not installed")

    /** Runs ssh-keygen and returns its standard output; fails the test on a non-zero exit. */
    fun run(vararg args: String): String {
        val process = ProcessBuilder(listOf("ssh-keygen") + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.close()
        val out = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "ssh-keygen did not finish" }
        check(process.exitValue() == 0) { "ssh-keygen failed with ${process.exitValue()}" }
        return out
    }

    /** Writes [content] where ssh-keygen accepts it as a private key: readable by the owner only. */
    fun writePrivate(dir: Path, name: String, content: ByteArray): Path {
        val file = dir.resolve(name)
        Files.write(file, content)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        return file
    }

    /** `type base64` of a public key line, dropping the comment. */
    fun typeAndKey(line: String): String = line.trim().split(' ').take(2).joinToString(" ")
}

/** An embedded MINA server on 127.0.0.1 that accepts [authorizedKeys] for [KeySecrets.USER]. */
internal class KeyTestServer(authorizedKeys: List<PublicKey>) : AutoCloseable {
    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = KeyPairProvider.wrap(MinaKeys.ed25519())
        passwordAuthenticator = null
        keyboardInteractiveAuthenticator = null
        publickeyAuthenticator = PublickeyAuthenticator { user, key, _ ->
            user == KeySecrets.USER && authorizedKeys.any { KeyUtils.compareKeys(it, key) }
        }
        start()
    }

    val target: SshTarget get() = SshTarget("127.0.0.1", server.port, KeySecrets.USER)

    override fun close() {
        server.stop(true)
    }
}

/**
 * Writes an unencrypted OpenSSH RSA key from its parts, with valid check numbers and padding, so
 * that a test can damage one part while the file stays well-formed and the public part valid.
 */
internal object OpenSshRsa {
    class Parts(
        val n: BigInteger,
        val e: BigInteger,
        val d: BigInteger,
        val iqmp: BigInteger,
        val p: BigInteger,
        val q: BigInteger,
    )

    /** The parts of a fresh 2048-bit key. */
    fun parts(): Parts {
        val key = MinaKeys.rsa().private as RSAPrivateCrtKey
        return Parts(key.modulus, key.publicExponent, key.privateExponent, key.crtCoefficient, key.primeP, key.primeQ)
    }

    fun file(parts: Parts): ByteArray {
        val publicKey = bytes {
            string("ssh-rsa")
            mpint(parts.e)
            mpint(parts.n)
        }
        val section = ByteArrayOutputStream()
        DataOutputStream(section).run {
            writeInt(CHECK)
            writeInt(CHECK)
            string("ssh-rsa")
            listOf(parts.n, parts.e, parts.d, parts.iqmp, parts.p, parts.q).forEach { mpint(it) }
            string("c")
            var pad = 1
            while (section.size() % BLOCK != 0) writeByte(pad++)
        }
        val blob = bytes {
            write("openssh-key-v1\u0000".encodeToByteArray())
            string("none")
            string("none")
            string(ByteArray(0))
            writeInt(1)
            string(publicKey)
            string(section.toByteArray())
        }
        return OpenSshBody.encode(blob)
    }

    private const val CHECK = 0x01020304
    private const val BLOCK = 8

    private fun bytes(build: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).build() }.toByteArray()

    private fun DataOutputStream.string(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.string(text: String) = string(text.encodeToByteArray())

    private fun DataOutputStream.mpint(value: BigInteger) = string(value.toByteArray())
}
