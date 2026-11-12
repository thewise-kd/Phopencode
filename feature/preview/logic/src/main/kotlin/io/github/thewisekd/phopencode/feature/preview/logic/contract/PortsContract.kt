package io.github.thewisekd.phopencode.feature.preview.logic.contract

/**
 * The port picker, `PreviewRoute.Ports` (P13.8 logic, P13.9 screen): the server's listening ports
 * from [PreviewPortsSource], the opencode one marked and not openable, and a field to enter a port
 * by hand.
 *
 * Rules the logic keeps:
 * - Loading lists the ports once; [PortsIntent.Refresh] lists them again, one listing at a time.
 *   Nothing but the listing runs on the server.
 * - [PortsIntent.Open] and [PortsIntent.OpenManualPort] emit [PortsEvent.OpenPage]; the opencode
 *   server's port is never opened.
 * - A manual entry is accepted only as a whole number in 1..65535 ([manualProblem] says why not).
 * - An empty list explains that the user, or opencode, must start the dev server.
 *
 * @property ports Ascending; the opencode one has [PreviewPort.isOpencode].
 * @property unsupported The server offers no way to list its ports.
 * @property error The last listing failed, and why.
 * @property manualText What the manual field shows. Prints masked, as everything the user types.
 * @property manualProblem Why [manualText] cannot be opened; null when it can or is untouched.
 */
data class PortsState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val ports: List<PreviewPort> = emptyList(),
    val unsupported: Boolean = false,
    val error: PreviewFailure? = null,
    val manualText: String = "",
    val manualProblem: ManualPortProblem? = null,
) {
    /** A listing runs: no other may start. */
    val busy: Boolean
        get() = loading || refreshing

    override fun toString(): String =
        "PortsState(loading=$loading, refreshing=$refreshing, ports=$ports, unsupported=$unsupported, " +
            "error=$error, manualText=***, manualProblem=$manualProblem)"
}

enum class ManualPortProblem {
    Empty,

    /** Not a whole number. */
    NotANumber,

    /** Outside 1..65535. */
    OutOfRange,

    /** The opencode server's port. */
    OpencodePort,
}

sealed interface PortsIntent {
    data object Refresh : PortsIntent

    /** Opens a listed port. */
    data class Open(val port: Int) : PortsIntent

    data class EditManualPort(val text: String) : PortsIntent {
        override fun toString(): String = "EditManualPort(***)"
    }

    /** Opens the manual entry when it is valid. */
    data object OpenManualPort : PortsIntent

    /** Clears [PortsState.error]. */
    data object DismissError : PortsIntent

    data object Close : PortsIntent
}

sealed interface PortsEvent {
    /** Opens `PreviewRoute.Page(port)`. */
    data class OpenPage(val port: Int) : PortsEvent {
        init {
            requirePort(port)
        }
    }

    data object Close : PortsEvent
}

interface PortsPresenter : Presenter<PortsState, PortsIntent, PortsEvent>
