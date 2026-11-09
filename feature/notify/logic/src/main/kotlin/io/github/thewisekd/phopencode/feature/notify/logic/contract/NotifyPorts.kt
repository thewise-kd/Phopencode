package io.github.thewisekd.phopencode.feature.notify.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The notification service on the server of the active connection, as [NotifyService.status] sees it. */
enum class NotifyServiceStatus {
    /** No service, or only part of one, is on the server. */
    NotInstalled,

    /** Installed, not running. Installing again starts it. */
    Stopped,

    Running,
}

/**
 * The port the setup manages the notification service through (`notifyd.pyz`, `helper/`), on the
 * server of the connection standing when a call starts. P11.8 does the work in `core/remote/…/notify/`
 * (python3 version check, upload, the 0600 config, start, status, removal); core cannot see this
 * contract, so an adapter in the notify ui module binds this port to it (`setup/RemoteNotifyService`,
 * bound in `NotifyModule`).
 *
 * Rules for every implementation:
 * - The topic and the opencode server's password reach the server only inside the 0600 config
 *   file, written over SFTP or stdin: never on a command line, in an environment variable, or in a
 *   log, a message or an exception.
 * - Every function throws [NotifyException] and nothing else, apart from `CancellationException`,
 *   which passes through so that cancelling still works. Without a connection it throws
 *   [NotifyFailure.NotConnected].
 */
interface NotifyService {
    /** Whether the service is installed and running. Never installs or starts anything. */
    suspend fun status(): NotifyServiceStatus

    /**
     * Installs the service with [settings], or reinstalls it over an older install, and starts it;
     * [status] is [NotifyServiceStatus.Running] afterwards. The opencode server's address and
     * password come from the connection, not from the caller.
     *
     * Fails with [NotifyFailure.PythonMissing] or [NotifyFailure.PythonTooOld] before anything is
     * written, and with [NotifyFailure.InstallFailed] when a step on the server fails.
     */
    suspend fun install(settings: NtfySettings)

    /**
     * Stops the service and removes everything it put on the server, config included; [status] is
     * [NotifyServiceStatus.NotInstalled] afterwards. Does nothing when there is no service.
     */
    suspend fun remove()

    /**
     * Sends one test notification with fixed text to [settings]' topic from the server, the way
     * the service sends its own, so the whole path to the phone is tried. Fails with
     * [NotifyFailure.NtfyFailed] when the ntfy server does not take it, and with
     * [NotifyFailure.PythonMissing] or [NotifyFailure.PythonTooOld] as [install] does.
     */
    suspend fun sendTest(settings: NtfySettings)
}

/**
 * The port the pending list reads the server through: the client of the active connection.
 * Pending items come from `InteractionApi.listForms` and `listPermissions` and follow the client's
 * `EventApi.events`.
 *
 * Implementations never log a form, a permission request or a server error.
 */
interface NotifyConnection {
    /**
     * The generation of the connection standing now (`ConnectionState.Connected.generation`), null
     * while there is none; emits the current value on collection, then each change once. The
     * pending list starts over on each new generation, so items of one connection, or one server,
     * are never shown for another.
     */
    val generations: Flow<Long?>

    /**
     * Runs [block] with the client of the connection standing right now and that connection's
     * generation. [block] may run long, for example to follow the client's event stream; it ends
     * when the client is closed, whose calls then fail with [ApiError.Closed].
     *
     * @throws ApiException With [ApiError.Closed] when no connection stands, without running
     *   [block]; or whatever [block] throws, which for the client is an [ApiException] too.
     */
    suspend fun <T> call(block: suspend (client: OpencodeClient, generation: Long) -> T): T
}

/**
 * [NotifyConnection] on the app's connection state, `ConnectionManager.state`: a call takes the
 * client of the `Connected` state current when it starts. If that connection ends meanwhile, the
 * client's calls fail with [ApiError.Closed]; they never move to a later connection.
 */
class ActiveNotifyConnection(private val state: StateFlow<ConnectionState>) : NotifyConnection {
    override val generations: Flow<Long?> =
        state.map { (it as? ConnectionState.Connected)?.generation }.distinctUntilChanged()

    override suspend fun <T> call(block: suspend (client: OpencodeClient, generation: Long) -> T): T {
        val current = state.value as? ConnectionState.Connected ?: throw ApiException(ApiError.Closed)
        return block(current.client, current.generation)
    }

    override fun toString(): String = "ActiveNotifyConnection"
}
