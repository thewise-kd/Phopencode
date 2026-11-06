package io.github.thewisekd.phopencode.feature.connect.logic.progress

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressAction
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressStep

/**
 * What the screen shows for a failed connect: one short [error] and the [actions] that can help.
 * Retry is always among them when the failure came from a connect, because the user may fix the
 * cause elsewhere (the server form, the host key screen, the server itself) and come back.
 *
 * @property step The step this error can only come from, or null when it may come from several.
 *   The manager's state is a conflated flow, so a step that fails at once may never be seen; the
 *   error itself tells.
 * @property port The port the error names, for [ProgressError.ServerNotOnPort] only.
 */
internal data class ProgressFailure(
    val error: ProgressError,
    val actions: Set<ProgressAction>,
    val step: ProgressStep? = null,
    val port: Int? = null,
) {
    /** The `opencode serve` password is unknown or refused, so the screen asks for it at once. */
    val asksPassword: Boolean get() = ProgressAction.EnterServerPassword in actions
}

private val RETRY = setOf(ProgressAction.Retry)
private val EDIT = setOf(ProgressAction.EditServer, ProgressAction.Retry)
private val PASSWORD = setOf(ProgressAction.EnterServerPassword, ProgressAction.Retry)
private val SSH = ProgressStep.Ssh
private val SERVER = ProgressStep.Server
private val API = ProgressStep.Api

/** Maps every [ConnectionError] to a [ProgressFailure]; nothing of the error's detail is kept. */
internal fun progressFailure(error: ConnectionError): ProgressFailure = when (error) {
    ConnectionError.InvalidProfile -> ProgressFailure(ProgressError.InvalidProfile, EDIT, SSH)
    ConnectionError.SecretMissing -> ProgressFailure(ProgressError.SecretMissing, EDIT, SSH)
    // The login secret, or the `opencode serve` credential on the server step.
    ConnectionError.SecretUnreadable -> ProgressFailure(ProgressError.SecretUnreadable, EDIT)
    ConnectionError.AuthenticationFailed -> ProgressFailure(ProgressError.AuthenticationFailed, EDIT, SSH)
    // A wrong host or port looks the same as a server that is down.
    ConnectionError.Unreachable -> ProgressFailure(ProgressError.Unreachable, EDIT, SSH)
    ConnectionError.Timeout -> ProgressFailure(ProgressError.Timeout, EDIT, SSH)
    ConnectionError.StorageUnavailable -> ProgressFailure(ProgressError.StorageUnavailable, RETRY)
    ConnectionError.Disconnected -> ProgressFailure(ProgressError.Disconnected, RETRY)
    ConnectionError.ServerLost -> ProgressFailure(ProgressError.ServerLost, RETRY)
    // A running server is only ever waited for: never stopped, never started next to.
    ConnectionError.ServerUnresponsive -> ProgressFailure(ProgressError.ServerUnresponsive, RETRY, SERVER)
    ConnectionError.ServerUnusable -> ProgressFailure(ProgressError.ServerUnusable, RETRY, SERVER)
    // The port is the profile's: the user can fix or clear it, and nothing runs on it to wait for.
    is ConnectionError.ServerNotOnPort -> ProgressFailure(ProgressError.ServerNotOnPort, EDIT, SERVER, error.port)
    ConnectionError.OpencodeMissing -> ProgressFailure(ProgressError.OpencodeMissing, RETRY, SERVER)
    ConnectionError.ServerPasswordUnknown -> ProgressFailure(ProgressError.ServerPasswordUnknown, PASSWORD, SERVER)
    is ConnectionError.HostKeyRejected -> ProgressFailure(ProgressError.HostKeyRejected, RETRY, SSH)
    is ConnectionError.HostKeyChanged -> ProgressFailure(
        ProgressError.HostKeyChanged,
        setOf(ProgressAction.ReviewHostKey, ProgressAction.Retry),
        SSH,
    )
    is ConnectionError.ServerDetectionFailed -> ProgressFailure(ProgressError.ServerDetectionFailed, RETRY, SERVER)
    is ConnectionError.ServerStartFailed -> ProgressFailure(ProgressError.ServerStartFailed, RETRY, SERVER)
    is ConnectionError.Api -> when (error.error) {
        ApiError.Unauthorized -> ProgressFailure(ProgressError.ServerPasswordRejected, PASSWORD, API)
        is ApiError.VersionTooOld -> ProgressFailure(ProgressError.ServerVersionTooOld, RETRY, API)
        // Also a dropped event stream after the connection stood; the screen is gone by then.
        else -> ProgressFailure(ProgressError.ApiFailed, RETRY, API)
    }
    is ConnectionError.Unexpected -> ProgressFailure(ProgressError.Unexpected, RETRY)
}
