package io.github.thewisekd.phopencode.feature.connect.logic.contract

/** The steps of a connect, as `ConnectionState` walks them; the screen's step indicator. */
enum class ProgressStep {
    /** Reading the login secret, opening SSH and logging in. */
    Ssh,

    /** The server's host key waits for the user's approval. */
    HostApproval,

    /** Finding the running `opencode serve`, or starting one. */
    Server,

    /** Checking the server's API and version. */
    Api,

    Connected,
}

/**
 * Why the connect stopped, short enough for one line. The presenter maps each
 * `ConnectionError` to one of these and to the [ProgressAction]s that can help.
 */
enum class ProgressError {
    InvalidProfile,
    SecretMissing,
    SecretUnreadable,
    StorageUnavailable,
    Unreachable,
    Timeout,
    Disconnected,
    AuthenticationFailed,
    HostKeyRejected,
    HostKeyChanged,
    OpencodeMissing,

    /** A server runs that this app did not start, and its password is not known. */
    ServerPasswordUnknown,

    /** The server refused the `opencode serve` password. */
    ServerPasswordRejected,

    ServerVersionTooOld,
    ServerUnresponsive,
    ServerUnusable,
    ServerDetectionFailed,
    ServerStartFailed,
    ServerLost,

    /** The server's API failed in another way. */
    ApiFailed,

    Unexpected,
}

/** What the user can do about a [ProgressError]. */
enum class ProgressAction {
    Retry,

    /** Opens the server form, for example to fix the host or the login. */
    EditServer,

    /** Opens the host key screen for a changed key. */
    ReviewHostKey,

    /** Asks for the `opencode serve` password of a server this app did not start. */
    EnterServerPassword,
}

/**
 * Connecting to a saved server (P4.14, P4.15): connects to a running `opencode serve`, or starts
 * one, through `ConnectionManager`.
 *
 * @property serverName The server's display name; printing leaves it out.
 * @property step The step running now, or the one that failed when [error] is set.
 * @property actions What can help with [error]; empty when there is none.
 * @property askingServerPassword The `opencode serve` password is being asked for.
 */
data class ProgressState(
    val serverName: String = "",
    val step: ProgressStep = ProgressStep.Ssh,
    val error: ProgressError? = null,
    val actions: Set<ProgressAction> = emptySet(),
    val askingServerPassword: Boolean = false,
) {
    override fun toString(): String =
        "ProgressState(step=$step, error=$error, actions=$actions, askingServerPassword=$askingServerPassword)"
}

sealed interface ProgressIntent {
    /** Connects again after [ProgressState.error]. */
    data object Retry : ProgressIntent

    /** Stops the connect and leaves the screen. */
    data object Cancel : ProgressIntent

    data object EditServer : ProgressIntent

    data object ReviewHostKey : ProgressIntent

    data object EnterServerPassword : ProgressIntent

    /**
     * The `opencode serve` password: stored in `SecretStore` as the profile's credential, then the
     * connect starts again.
     */
    data class SubmitServerPassword(val password: String) : ProgressIntent {
        override fun toString(): String = "SubmitServerPassword(***)"
    }

    data object DismissServerPassword : ProgressIntent
}

sealed interface ProgressEvent {
    /** The connection stands; on to the folder picker. */
    data object Connected : ProgressEvent

    /** The connection waits for a host key decision, or stopped on a changed key. */
    data object OpenHostKey : ProgressEvent

    data object OpenServerForm : ProgressEvent

    data object Close : ProgressEvent
}

interface ProgressPresenter : Presenter<ProgressState, ProgressIntent, ProgressEvent>
