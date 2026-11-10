package io.github.thewisekd.phopencode.e2e

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue

/**
 * The server side of the end-to-end tests, as `.github/workflows/emulator.yml` sets it up on the
 * runner: an sshd of the runner's user, and an `opencode serve` of the same user on the runner's
 * loopback, with no LLM provider, working in a scratch git repository. Nothing about it lives in
 * the repository; the job generates every credential for its run.
 *
 * Arguments: the SSH ones of the SSH tests (`sshHost`, `sshPort`, `sshUser`, `sshHostKey`, the
 * host key's `SHA256:` fingerprint, and `sshKey`, the user's OpenSSH private key in base64), then
 * `opencodePort`, the server's loopback port, `opencodePasswordFile`, the absolute path on the
 * runner of a file holding the server's password, and `projectDir`, the absolute path of the
 * project folder. The password itself is never an argument: [readOpencodePassword] reads it over
 * SSH, as a user would look it up on the server. With `requireE2e=true` a missing argument fails
 * the test; without it the test is skipped, so that a plain connected run on a developer's
 * emulator still passes.
 *
 * The tests' own SSH connection, here, is only the user's side of the setup: reading the password
 * and removing what a test left in the project folder. Everything under test goes through the app.
 */
internal class E2eEnvironment private constructor(
    val target: SshTarget,
    val hostKeyFingerprint: String,
    private val userKey: String,
    val opencodePort: Int,
    private val passwordFile: String,
    val projectDir: String,
) {
    private val connector = SshjConnector()

    /** The user's private key, as the app stores it for a profile. The caller wipes the copy. */
    fun userKeyBytes(): ByteArray = userKey.toByteArray(Charsets.US_ASCII)

    /** The `opencode serve` password, read from its file on the server. */
    suspend fun readOpencodePassword(): String {
        val folder = passwordFile.substringBeforeLast('/')
        val name = passwordFile.substringAfterLast('/')
        val bytes = sftp(folder) { it.read(name, MAX_PASSWORD_BYTES) }
        try {
            val password = bytes.decodeToString().trim()
            check(password.isNotEmpty()) { "The opencode password file is empty" }
            return password
        } finally {
            bytes.fill(0)
        }
    }

    /** Removes [name] from the project folder, if it is there; for a test's cleanup. */
    suspend fun deleteFromProject(name: String) {
        runCatching { sftp(projectDir) { it.delete(name) } }
    }

    private suspend fun <T> sftp(root: String, block: suspend (SftpClient) -> T): T {
        val credential = SshCredential.PrivateKey(userKey.toCharArray())
        val verifier = HostKeyVerifier { _, key ->
            if (key.fingerprintSha256 == hostKeyFingerprint) HostKeyDecision.Accept else HostKeyDecision.Reject
        }
        val session = try {
            connector.connect(target, credential, verifier, OPTIONS)
        } finally {
            credential.wipe()
        }
        return session.use { it.openSftp(root).use { sftp -> block(sftp) } }
    }

    override fun toString(): String = "E2eEnvironment(***)"

    companion object {
        private val OPTIONS = SshConnectOptions(connectTimeout = 60.seconds)
        private const val MAX_PASSWORD_BYTES = 1024L

        private val NAMES = listOf(
            "sshHost", "sshPort", "sshUser", "sshHostKey", "sshKey",
            "opencodePort", "opencodePasswordFile", "projectDir",
        )

        fun fromArguments(): E2eEnvironment {
            val arguments = InstrumentationRegistry.getArguments()
            val required = arguments.getString("requireE2e") == "true"
            val missing = NAMES.filter { arguments.getString(it).isNullOrBlank() }
            if (missing.isNotEmpty()) {
                val message = "No end-to-end server: missing instrumentation arguments $missing"
                if (required) fail(message)
                assumeTrue(message, false)
            }
            return E2eEnvironment(
                target = SshTarget(arguments.text("sshHost"), arguments.port("sshPort"), arguments.text("sshUser")),
                hostKeyFingerprint = arguments.text("sshHostKey"),
                userKey = String(Base64.getDecoder().decode(arguments.text("sshKey")), Charsets.US_ASCII),
                opencodePort = arguments.port("opencodePort"),
                passwordFile = arguments.text("opencodePasswordFile"),
                projectDir = arguments.text("projectDir").trimEnd('/'),
            )
        }

        private fun Bundle.text(name: String): String = checkNotNull(getString(name)).trim()

        private fun Bundle.port(name: String): Int = text(name).toInt()
    }
}
