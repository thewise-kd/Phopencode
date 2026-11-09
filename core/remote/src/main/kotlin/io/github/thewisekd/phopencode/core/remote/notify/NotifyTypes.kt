package io.github.thewisekd.phopencode.core.remote.notify

import io.github.thewisekd.phopencode.core.api_http.base.BasicCredentials
import io.github.thewisekd.phopencode.core.remote.connection.BoundConnection
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import java.io.InputStream

/** The files of `helper/` the installer uploads, under the names the scripts expect. */
enum class NotifyHelperFile(val fileName: String) {
    /** `notifyd.pyz`, built by `scripts/build_helper.py`. */
    Service("notifyd.pyz"),

    /** `helper/install.sh`. */
    Install("install.sh"),

    /** `helper/uninstall.sh`. */
    Uninstall("uninstall.sh"),
}

/**
 * Where the installer reads the files it uploads; the app bundles them. Each call opens a new
 * stream, which the installer closes. A failure to open or read one is an [java.io.IOException].
 */
fun interface NotifyHelperFiles {
    fun open(file: NotifyHelperFile): InputStream
}

/**
 * How notifyd reaches `opencode serve` from the server itself: [url] as the server sees it (a
 * loopback address), with HTTP Basic [username] and [password]. The password goes only into the
 * 0600 config file.
 *
 * Whoever makes one owns it and closes it, which wipes [password]; the installer never closes it.
 * Prints nothing of its content.
 */
class OpencodeAccess(
    val url: String,
    val password: SecretValue,
    val username: String = BasicCredentials.DEFAULT_USERNAME,
) : AutoCloseable {
    override fun close() = password.close()

    override fun toString(): String = "OpencodeAccess(***)"
}

/**
 * Where the opencode server's [OpencodeAccess] comes from for a connection. The installer cannot
 * know it: the connection holds it.
 */
fun interface OpencodeAccessSource {
    /**
     * The access of the `opencode serve` that [connection] reached, owned by the caller, who closes
     * it as soon as it is used.
     *
     * @throws NotifyInstallException With [NotifyInstallFailure.AccessUnavailable] when it is not known.
     */
    suspend fun access(connection: BoundConnection): OpencodeAccess

    companion object {
        /**
         * The connection's own: [BoundConnection.opencode]'s URL, and the password read again from
         * the secret store through its ref ([BoundConnection.readOpencodePassword]). Fails with
         * [NotifyInstallFailure.AccessUnavailable] when the connection knows no ref or the secret
         * can no longer be read.
         */
        val FromConnection: OpencodeAccessSource = OpencodeAccessSource { connection ->
            val login = connection.opencode ?: throw NotifyInstallException(NotifyInstallFailure.AccessUnavailable)
            val password = connection.readOpencodePassword()
                ?: throw NotifyInstallException(NotifyInstallFailure.AccessUnavailable)
            OpencodeAccess(login.baseUrl, password)
        }
    }
}

/**
 * The ntfy topic notifyd publishes to on [server], an `https` origin. The topic is a secret: it
 * goes only into a 0600 config file, and it does not print. Neither does [server], which may be a
 * private address.
 */
class NtfyTarget(val server: String, private val topic: String) {
    fun revealTopic(): String = topic

    override fun toString(): String = "NtfyTarget(***)"
}

/** The notification service on the server, as `install.sh --status` reports it. */
enum class NotifyInstallStatus {
    /** No service manager entry and no notifyd running. */
    NotInstalled,

    /** Installed (systemd unit or cron line), not running. */
    Stopped,

    /** notifyd runs. */
    Running,
}

/** Why the installer failed, apart from SSH failures, which it passes on as they are. */
enum class NotifyInstallFailure {
    /** The server has no `python3`, or it does not run; nothing was written. */
    PythonMissing,

    /** The server's `python3` is older than 3.9; nothing was written. */
    PythonTooOld,

    /**
     * A step on the server failed or answered something unexpected: making the private folder,
     * uploading, writing the config, a script's exit, or reading a bundled file.
     */
    StepFailed,

    /** `notifyd --send-test` ran, but the ntfy server did not take the notification. */
    NtfyRejected,

    /** `install.sh --status` could not tell whether notifyd runs or how it is installed. */
    StatusUnknown,

    /**
     * The opencode server's address and password are not known for the connection: it kept no
     * ref, or the secret under it is gone or cannot be read.
     */
    AccessUnavailable,
}

/**
 * What the installer throws for [failure]. The message names [failure] only and no cause is
 * chained: never a script's output, a path, the password or the topic. So it is safe to log.
 */
class NotifyInstallException(val failure: NotifyInstallFailure) : Exception("Notify install failure: $failure")
