package io.github.thewisekd.phopencode.ssh

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue

/**
 * The sshd that `.github/workflows/emulator.yml` starts on the runner, described by instrumentation
 * arguments. Nothing about it lives in the repository: the job generates the host key and the user
 * key, and passes them here.
 *
 * Arguments: `sshHost`, `sshPort`, `sshUser`, `sshHostKey` (the host key's `SHA256:` fingerprint),
 * `sshKey` (the user's OpenSSH private key, base64 encoded so it fits on one line), `sftpRoot` (an
 * absolute folder the tests may write in), `echoPort` (a byte echo server on the runner's loopback)
 * and `statsPort` (on the runner's loopback too: answers with the number of open echo connections,
 * then closes). With `requireSsh=true` a missing argument fails the test; without it the test is
 * skipped, so that a plain `connectedDebugAndroidTest` on a developer's emulator still passes.
 */
internal class SshTestServer private constructor(
    val target: SshTarget,
    val hostKeyFingerprint: String,
    private val userKey: String,
    val sftpRoot: String,
    val echoPort: Int,
    val statsPort: Int,
) {
    /** The real connector with its default features: commands, SFTP and tunnels. */
    private val connector = SshjConnector()

    /** A new credential each time, since [connect] wipes the one it used. */
    fun credential(): SshCredential = SshCredential.PrivateKey(userKey.toCharArray())

    /** Accepts exactly [expected] and records every key the server presented. */
    fun verifier(expected: String = hostKeyFingerprint, seen: MutableList<HostKey>? = null) =
        HostKeyVerifier { _, key ->
            seen?.add(key)
            if (key.fingerprintSha256 == expected) HostKeyDecision.Accept else HostKeyDecision.Reject
        }

    suspend fun connect(verifier: HostKeyVerifier = verifier()): SshSession {
        val credential = credential()
        try {
            return connector.connect(target, credential, verifier, OPTIONS)
        } finally {
            credential.wipe()
        }
    }

    override fun toString(): String = "SshTestServer(***)"

    companion object {
        /** An emulator is slow, API 26 most of all; this is generous on purpose. */
        val OPTIONS = SshConnectOptions(connectTimeout = 60.seconds)

        fun fromArguments(): SshTestServer {
            val arguments = InstrumentationRegistry.getArguments()
            val required = arguments.getString("requireSsh") == "true"
            val missing = NAMES.filter { arguments.getString(it).isNullOrBlank() }
            if (missing.isNotEmpty()) {
                val message = "No test sshd: missing instrumentation arguments $missing"
                if (required) fail(message)
                assumeTrue(message, false)
            }
            return SshTestServer(
                target = SshTarget(arguments.text("sshHost"), arguments.port("sshPort"), arguments.text("sshUser")),
                hostKeyFingerprint = arguments.text("sshHostKey"),
                userKey = String(Base64.getDecoder().decode(arguments.text("sshKey")), Charsets.US_ASCII),
                sftpRoot = arguments.text("sftpRoot"),
                echoPort = arguments.port("echoPort"),
                statsPort = arguments.port("statsPort"),
            )
        }

        private val NAMES = listOf("sshHost", "sshPort", "sshUser", "sshHostKey", "sshKey", "sftpRoot", "echoPort", "statsPort")

        private fun Bundle.text(name: String): String = checkNotNull(getString(name)).trim()

        private fun Bundle.port(name: String): Int = text(name).toInt()
    }
}
