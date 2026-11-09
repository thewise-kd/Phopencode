package io.github.thewisekd.phopencode.feature.notify.ui.setup

import io.github.thewisekd.phopencode.core.remote.connection.BoundConnection
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.NotConnectedException
import io.github.thewisekd.phopencode.core.remote.notify.NotifyHelperFile
import io.github.thewisekd.phopencode.core.remote.notify.NotifyHelperFiles
import io.github.thewisekd.phopencode.core.remote.notify.NotifyInstallException
import io.github.thewisekd.phopencode.core.remote.notify.NotifyInstallFailure
import io.github.thewisekd.phopencode.core.remote.notify.NotifyInstallStatus
import io.github.thewisekd.phopencode.core.remote.notify.NotifyInstaller
import io.github.thewisekd.phopencode.core.remote.notify.NtfyTarget
import io.github.thewisekd.phopencode.core.remote.notify.OpencodeAccessSource
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyException
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyService
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyServiceStatus
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfySettings
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [NotifyService] on the remote installation in `core/remote/…/notify/` ([NotifyInstaller]), which
 * cannot see this feature. Each call runs on the connection standing when it starts
 * ([ConnectionManager.withConnection]), uploading the helper files the app bundles
 * ([BundledNotifyHelper]).
 *
 * Errors become [NotifyException] as the port says, and nothing else escapes but
 * `CancellationException`:
 * - no connection, or it ended during the call: [NotifyFailure.NotConnected];
 * - an SSH timeout: [NotifyFailure.Unreachable];
 * - the installer's failures: python missing or too old as such, a refused test notification as
 *   [NotifyFailure.NtfyFailed], a status nobody can tell as [NotifyFailure.Unknown], any failed
 *   step on the server (an SFTP failure included) as [NotifyFailure.InstallFailed];
 * - anything else: [NotifyFailure.Unknown].
 *
 * No message or cause is carried over, so no server text, path or secret reaches the caller.
 *
 * Calls run one at a time: each waits for the one under way to end, however it ends.
 *
 * @param connections Runs a call on the standing connection; [ConnectionManager.withConnection].
 * @param access The opencode server's address and password for [install]: the connection's own,
 *   read again from the secret store per install and wiped once the install ends, however it ends.
 *   A connection without a readable password makes [install] fail with
 *   [NotifyFailure.InstallFailed]: the connection stands, but a step of the install cannot be done.
 */
class RemoteNotifyService internal constructor(
    private val connections: Connections,
    private val files: NotifyHelperFiles,
    private val access: OpencodeAccessSource,
) : NotifyService {
    /** The app's service, made by `NotifyModule`. */
    constructor(connection: ConnectionManager) : this(
        connections = Connections.of(connection),
        files = BundledNotifyHelper,
        access = OpencodeAccessSource.FromConnection,
    )

    /** [ConnectionManager.withConnection], as a seam for tests. */
    internal interface Connections {
        suspend fun <T> withConnection(block: suspend (BoundConnection) -> T): T

        companion object {
            fun of(manager: ConnectionManager): Connections = object : Connections {
                override suspend fun <T> withConnection(block: suspend (BoundConnection) -> T): T =
                    manager.withConnection(block)
            }
        }
    }

    override suspend fun status(): NotifyServiceStatus = onServer { installer, _ ->
        when (installer.status()) {
            NotifyInstallStatus.NotInstalled -> NotifyServiceStatus.NotInstalled
            NotifyInstallStatus.Stopped -> NotifyServiceStatus.Stopped
            NotifyInstallStatus.Running -> NotifyServiceStatus.Running
        }
    }

    override suspend fun install(settings: NtfySettings) = onServer { installer, connection ->
        access.access(connection).use { installer.install(it, settings.target()) }
    }

    override suspend fun remove() = onServer { installer, _ -> installer.remove() }

    override suspend fun sendTest(settings: NtfySettings) = onServer { installer, _ ->
        installer.sendTest(settings.target())
    }

    private fun NtfySettings.target() = NtfyTarget(server.url, topic.reveal())

    /**
     * Held across each call, so that no two calls of this service interleave their steps on the
     * server: a remove between another install's uninstall and its config write would leave a
     * restarted service or delete that install's config. The app has one service (`NotifyModule`
     * binds a single instance), so one lock covers every caller.
     */
    private val serverLock = Mutex()

    /**
     * Runs [block] on the connection standing when the call starts. Its generation is taken before
     * waiting for [serverLock]; if another connection stands once the lock is held, the call fails
     * with [NotConnectedException] and runs nothing, so a queued call never moves to another server.
     */
    private suspend fun <T> onServer(block: suspend (NotifyInstaller, BoundConnection) -> T): T = try {
        val started = connections.withConnection { it.generation }
        serverLock.withLock {
            connections.withConnection { connection ->
                if (connection.generation != started) throw NotConnectedException()
                block(NotifyInstaller.on(connection, files), connection)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw NotifyException(failureOf(failure))
    }

    override fun toString(): String = "RemoteNotifyService"

    internal companion object {
        fun failureOf(failure: Exception): NotifyFailure = when (failure) {
            is NotifyException -> failure.failure
            is NotConnectedException, is SshException.Disconnected -> NotifyFailure.NotConnected
            is SshException.Timeout -> NotifyFailure.Unreachable
            is SshException -> NotifyFailure.InstallFailed
            is NotifyInstallException -> when (failure.failure) {
                NotifyInstallFailure.PythonMissing -> NotifyFailure.PythonMissing
                NotifyInstallFailure.PythonTooOld -> NotifyFailure.PythonTooOld
                NotifyInstallFailure.StepFailed, NotifyInstallFailure.AccessUnavailable -> NotifyFailure.InstallFailed
                NotifyInstallFailure.NtfyRejected -> NotifyFailure.NtfyFailed
                NotifyInstallFailure.StatusUnknown -> NotifyFailure.Unknown
            }
            else -> NotifyFailure.Unknown
        }
    }
}

/**
 * The helper files the build bundles as Java resources (`bundleNotifyHelper` in this module's
 * build file): `notifyd.pyz` built from `helper/notifyd`, and `helper/`'s two scripts.
 */
internal object BundledNotifyHelper : NotifyHelperFiles {
    const val RESOURCE_PATH = "/io/github/thewisekd/phopencode/feature/notify/ui/helper/"

    override fun open(file: NotifyHelperFile): InputStream =
        BundledNotifyHelper::class.java.getResourceAsStream(RESOURCE_PATH + file.fileName)
            ?: throw FileNotFoundException("Bundled helper file is missing: ${file.fileName}")

    override fun toString(): String = "BundledNotifyHelper"
}
