package io.github.thewisekd.phopencode.core.remote.notify

import io.github.thewisekd.phopencode.core.remote.connection.BoundConnection
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.ExecResult
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SftpWriteOptions
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Installs, checks and removes the notification service (`notifyd.pyz`, `helper/`) on a server,
 * over one SSH connection's command runner [exec] and SFTP, and sends a test notification through
 * it. The scripts do the service work; this class uploads them and reads their results.
 *
 * Every operation that runs a script first makes a private folder (mode 0700, `mkdir -m 700`,
 * never an existing one) under a random name drawn on the phone, uploads what it needs there (each
 * file 0600), runs it, and removes the folder whatever happens, cancellation included. The name is
 * known before the folder is made, so a cancellation during the creation still removes it.
 *
 * - [install]: checks `python3` (3.9 or newer) before writing anything, runs `uninstall.sh` (a
 *   reinstall is an uninstall, then an install), writes the config file (0600, in a 0700 folder,
 *   atomically over SFTP) at notifyd's default path, then runs `install.sh <archive>`, which starts
 *   the service. A failed start removes the config again.
 * - [status]: `install.sh --status`.
 * - [remove]: `uninstall.sh`, which removes the config, waits for notifyd to exit by itself and
 *   removes the rest.
 * - [sendTest]: `python3 notifyd.pyz --send-test` with a temporary 0600 config in the private
 *   folder; the service's config is not touched.
 *
 * Secrets: the opencode password and the ntfy topic reach the server only inside a 0600 config
 * file written over SFTP; never on a command line, in an environment variable or on stdin, and
 * never in an exception. Nothing is logged.
 *
 * Server safety: nothing here stops, signals or kills a process, uses `sudo`, or touches anything
 * but the login user's files; the only process started is notifyd, by `install.sh`.
 *
 * Concurrency: the operations change shared state on the server (the service, its config) in
 * several steps, so calls must not overlap. This class does not serialize them: its caller holds
 * one lock across every call for a server (in the app, `RemoteNotifyService`, one per app).
 *
 * Failures: [NotifyInstallException]; an SSH failure (`SshException`, which includes SFTP ones)
 * passes through as it is, and so does `CancellationException`.
 *
 * @param openSftp Opens an SFTP client with root `/`; the installer closes it.
 */
class NotifyInstaller internal constructor(
    private val exec: RemoteExec,
    private val openSftp: suspend () -> SftpClient,
    private val files: NotifyHelperFiles,
    /** Names each private folder; tests fix it, the app draws it from a `SecureRandom`. */
    private val tempName: () -> String,
) {
    constructor(exec: RemoteExec, openSftp: suspend () -> SftpClient, files: NotifyHelperFiles) :
        this(exec, openSftp, files, { NotifyCommands.newTempName(RANDOM) })

    suspend fun status(): NotifyInstallStatus = inTemp(NotifyHelperFile.Install) { temp, _ ->
        val result = run(NotifyCommands.status(temp), STEP_TIMEOUT)
        if (!result.succeeded) throw failed()
        val match = result.lines().asReversed().firstNotNullOfOrNull(STATUS_LINE::matchEntire) ?: throw failed()
        val (manager, state) = match.destructured
        when {
            state == "running" -> NotifyInstallStatus.Running
            state == "unknown" || manager == "unknown" -> throw NotifyInstallException(NotifyInstallFailure.StatusUnknown)
            manager == "none" -> NotifyInstallStatus.NotInstalled
            else -> NotifyInstallStatus.Stopped
        }
    }

    /**
     * Installs, or reinstalls, the service for [target] with [access] and starts it. [access] stays
     * open: its owner closes it.
     */
    suspend fun install(access: OpencodeAccess, target: NtfyTarget) {
        val probe = checkPython()
        val base = probe.configBase ?: throw failed()
        val directory = "$base/${NotifyCommands.CONFIG_DIRECTORY}"
        val config = "$directory/${NotifyCommands.CONFIG_FILE}"
        inTemp(*NotifyHelperFile.entries.toTypedArray()) { temp, sftp ->
            val uninstalled = run(NotifyCommands.uninstall(temp), UNINSTALL_TIMEOUT)
            if (uninstalled.exitCode !in REMOVED) throw failed()
            if (!run(NotifyCommands.makeConfigDir(directory), STEP_TIMEOUT).succeeded) throw failed()
            var started = false
            // Guarded from the moment the write starts: a write can commit and still end in a
            // failure or a cancellation, and a config without a running service holds secrets for
            // nothing.
            try {
                writeConfig(sftp, config, access.url, access.username, access.password.revealText(), target)
                val installed = run(NotifyCommands.install(temp), INSTALL_TIMEOUT)
                when (installed.exitCode) {
                    0 -> if (installed.lines().none(INSTALLED_LINE::matches)) throw failed()
                    INSTALL_PYTHON_MISSING -> throw NotifyInstallException(NotifyInstallFailure.PythonMissing)
                    INSTALL_PYTHON_TOO_OLD -> throw NotifyInstallException(NotifyInstallFailure.PythonTooOld)
                    else -> throw failed()
                }
                started = true
            } finally {
                // Quietly and under NonCancellable (see [quietly]), so a cancellation still deletes it.
                if (!started) quietly { sftp.delete(config) }
            }
        }
    }

    /** Removes the service and its config; does nothing when none is installed. */
    suspend fun remove() {
        inTemp(NotifyHelperFile.Uninstall) { temp, _ ->
            if (run(NotifyCommands.uninstall(temp), UNINSTALL_TIMEOUT).exitCode !in REMOVED) throw failed()
        }
    }

    /**
     * Sends notifyd's fixed test notification to [target]. notifyd does not connect to opencode
     * for it, so the temporary config names a placeholder server and password.
     */
    suspend fun sendTest(target: NtfyTarget) {
        checkPython()
        inTemp(NotifyHelperFile.Service) { temp, sftp ->
            writeConfig(sftp, "$temp/${NotifyCommands.SEND_TEST_CONFIG}", PLACEHOLDER_URL, PLACEHOLDER_USER, PLACEHOLDER_PASSWORD, target)
            when (run(NotifyCommands.sendTest(temp), SEND_TEST_TIMEOUT).exitCode) {
                0 -> Unit
                SEND_TEST_REFUSED -> throw NotifyInstallException(NotifyInstallFailure.NtfyRejected)
                else -> throw failed()
            }
        }
    }

    private suspend fun checkPython(): NotifyCommands.Probe {
        val result = run(NotifyCommands.PROBE, STEP_TIMEOUT)
        if (!result.succeeded) throw failed()
        val probe = NotifyCommands.parseProbe(result.stdout.decodeToString()) ?: throw failed()
        val python = probe.python ?: throw NotifyInstallException(NotifyInstallFailure.PythonMissing)
        if (python < NotifyCommands.MIN_PYTHON) throw NotifyInstallException(NotifyInstallFailure.PythonTooOld)
        return probe
    }

    /**
     * Makes the private folder, uploads [uploads] into it, runs [block] with its path and the
     * SFTP client, and removes the folder afterwards, whatever happened.
     */
    private suspend fun <T> inTemp(
        vararg uploads: NotifyHelperFile,
        block: suspend (temp: String, sftp: SftpClient) -> T,
    ): T {
        val name = tempName()
        // From before the creation runs: it may make the folder and still end in a failure or a
        // cancellation. Only an answer that it made nothing (a refused base, an existing name)
        // turns the removal off, so a folder of someone else's is never removed.
        var made = true
        try {
            val result = run(NotifyCommands.makeTemp(name), STEP_TIMEOUT)
            if (!result.succeeded) {
                made = false
                throw failed()
            }
            val temp = NotifyCommands.parseTemp(result.stdout.decodeToString(), name) ?: throw failed()
            return openSftp().use { sftp ->
                uploads.forEach { file -> upload(sftp, file, NotifyCommands.file(temp, file)) }
                block(temp, sftp)
            }
        } finally {
            if (made) quietly { run(NotifyCommands.removeTemp(name), STEP_TIMEOUT) }
        }
    }

    private suspend fun upload(sftp: SftpClient, file: NotifyHelperFile, path: String) {
        val source: InputStream = try {
            files.open(file)
        } catch (_: IOException) {
            throw failed()
        }
        source.use { sftp.writeAtomic(path, it, PRIVATE_NEW) }
    }

    private suspend fun writeConfig(
        sftp: SftpClient,
        path: String,
        url: String,
        username: String,
        password: String,
        target: NtfyTarget,
    ) {
        val bytes = buildJsonObject {
            put("server_url", url)
            put("username", username)
            put("password", password)
            put("ntfy_server", target.server)
            put("ntfy_topic", target.revealTopic())
        }.toString().encodeToByteArray()
        try {
            sftp.writeAtomic(path, ByteArrayInputStream(bytes), PRIVATE_REPLACE)
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun run(command: String, timeout: Duration): ExecResult =
        exec.run(ExecRequest(command, stdin = null, timeout = timeout), MAX_OUTPUT_BYTES)

    /** Runs [block] even when cancelled, for a cleanup, and ignores how it fails. */
    private suspend fun quietly(block: suspend () -> Unit) {
        withContext(NonCancellable) {
            try {
                block()
            } catch (_: Exception) {
                // A cleanup that fails must not hide what went wrong before it.
            }
        }
    }

    private fun failed() = NotifyInstallException(NotifyInstallFailure.StepFailed)

    private fun ExecResult.lines(): List<String> = stdout.decodeToString().lines()

    override fun toString(): String = "NotifyInstaller"

    companion object {
        /** An installer on [connection]: its command runner and its SFTP with root `/`. */
        fun on(connection: BoundConnection, files: NotifyHelperFiles): NotifyInstaller =
            NotifyInstaller(connection.exec, { connection.openSftp("/") }, files)

        private val RANDOM = java.security.SecureRandom()

        private val STEP_TIMEOUT = 1.minutes
        private val INSTALL_TIMEOUT = 2.minutes

        /** `uninstall.sh` waits up to 60 seconds for notifyd to exit. */
        private val UNINSTALL_TIMEOUT = 3.minutes

        /** ntfy sends retry with backoff. */
        private val SEND_TEST_TIMEOUT = 5.minutes + 30.seconds

        private const val MAX_OUTPUT_BYTES = 64L * 1024

        /** `uninstall.sh`: removed (0), or removed but the crontab could not be read or is missing (4). */
        private val REMOVED = setOf(0, 4)

        private const val INSTALL_PYTHON_MISSING = 3
        private const val INSTALL_PYTHON_TOO_OLD = 4

        /** `notifyd --send-test`: ntfy did not accept the test notification. */
        private const val SEND_TEST_REFUSED = 3

        private val STATUS_LINE = Regex("""status (systemd|cron|none|unknown) (running|stopped|unknown)""")
        private val INSTALLED_LINE = Regex("""installed (systemd|cron)""")

        private val PRIVATE_NEW = SftpWriteOptions(overwrite = false, permissions = SftpWriteOptions.OWNER_ONLY)
        private val PRIVATE_REPLACE = SftpWriteOptions(overwrite = true, permissions = SftpWriteOptions.OWNER_ONLY)

        /** A test send never reaches opencode; config.py still wants these fields. */
        private const val PLACEHOLDER_URL = "http://127.0.0.1:9"
        private const val PLACEHOLDER_USER = "opencode"
        private const val PLACEHOLDER_PASSWORD = "placeholder"
    }
}
