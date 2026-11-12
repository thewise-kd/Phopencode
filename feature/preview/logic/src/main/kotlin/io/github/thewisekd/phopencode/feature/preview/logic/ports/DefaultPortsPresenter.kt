package io.github.thewisekd.phopencode.feature.preview.logic.ports

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ManualPortProblem
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsEvent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsPresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewException
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPort
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPortList
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPortsSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The port picker's logic (P13.8): lists the server's listening ports, marks the opencode one and
 * never opens it, and checks the port entered by hand.
 *
 * The only thing it asks the server for is [PreviewPortsSource.list]; opening a port only emits
 * [PortsEvent.OpenPage], and the page screen opens the tunnel. One listing runs at a time: a
 * [PortsIntent.Refresh] while one runs is ignored. A manual entry submitted before any listing has
 * succeeded, while one runs, is ignored (see [openManual]). Nothing is logged.
 *
 * @param ports The listening ports of the server of the active connection.
 * @param scope The destination's scope; work started here stops with it.
 */
class DefaultPortsPresenter(
    private val ports: PreviewPortsSource,
    private val scope: CoroutineScope,
) : PortsPresenter {
    private val mutableState = MutableStateFlow(PortsState())

    override val state: StateFlow<PortsState> = mutableState.asStateFlow()

    /** Unlimited, so no event is ever dropped while nobody collects. */
    private val eventChannel = Channel<PortsEvent>(Channel.UNLIMITED)

    override val events: Flow<PortsEvent> = eventChannel.receiveAsFlow()

    /** Guards read-modify-write of [mutableState], so a check and the change it allows are one step. */
    private val lock = Any()

    /**
     * The opencode server's ports from the last successful listing, guarded by [lock]. A failed or
     * unsupported listing keeps them, so the manual entry still refuses the opencode port; only a
     * new successful listing replaces them.
     */
    private var opencodePorts: Set<Int> = emptySet()

    /** A listing has succeeded at least once, so [opencodePorts] is known; guarded by [lock]. */
    private var listedOnce = false

    init {
        // The initial state is already loading; the listing itself starts once the scope runs.
        list()
    }

    override fun onIntent(intent: PortsIntent) {
        when (intent) {
            PortsIntent.Refresh -> refresh()
            is PortsIntent.Open -> open(intent.port)
            is PortsIntent.EditManualPort -> edit { it.copy(manualText = intent.text, manualProblem = problemWhileTyping(intent.text)) }
            PortsIntent.OpenManualPort -> openManual()
            PortsIntent.DismissError -> edit { it.copy(error = null) }
            PortsIntent.Close -> send(PortsEvent.Close)
        }
    }

    /** Changes the state under [lock]. */
    private inline fun edit(change: (PortsState) -> PortsState) {
        synchronized(lock) { mutableState.value = change(mutableState.value) }
    }

    // Listing

    private fun refresh() {
        val started = synchronized(lock) {
            val current = mutableState.value
            if (current.busy) {
                false
            } else {
                mutableState.value = current.copy(refreshing = true)
                true
            }
        }
        if (started) list()
    }

    /** Runs one listing; the caller has already marked the state busy. */
    private fun list() {
        scope.launch {
            yield()
            val outcome = try {
                Outcome.Done(ports.list())
            } catch (e: CancellationException) {
                throw e
            } catch (e: PreviewException) {
                Outcome.Failed(e.failure)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Outcome.Failed(PreviewFailure.Unknown)
            }
            edit { current -> show(current, outcome) }
        }
    }

    /** Runs under [lock]. */
    private fun show(current: PortsState, outcome: Outcome): PortsState {
        val done = current.copy(loading = false, refreshing = false)
        val shown = when (outcome) {
            is Outcome.Failed -> done.copy(ports = emptyList(), unsupported = false, error = outcome.failure)
            is Outcome.Done -> when (val list = outcome.list) {
                PreviewPortList.Unsupported -> done.copy(ports = emptyList(), unsupported = true, error = null)
                is PreviewPortList.Listed -> {
                    val tidied = tidy(list.ports)
                    opencodePorts = tidied.filter { it.isOpencode }.mapTo(HashSet()) { it.port }
                    listedOnce = true
                    done.copy(ports = tidied, unsupported = false, error = null)
                }
            }
        }
        // The opencode port may have appeared or moved: check the manual entry against the new list.
        val problem = if (shown.manualProblem == null || shown.manualProblem == ManualPortProblem.OpencodePort) {
            problemWhileTyping(shown.manualText)
        } else {
            shown.manualProblem
        }
        return shown.copy(manualProblem = problem)
    }

    /** Ascending, each port once; a port listed twice is the opencode one if either says so. */
    private fun tidy(listed: List<PreviewPort>): List<PreviewPort> =
        listed.groupBy { it.port }
            .map { (port, same) -> PreviewPort(port, isOpencode = same.any { it.isOpencode }) }
            .sortedBy { it.port }

    private sealed interface Outcome {
        class Done(val list: PreviewPortList) : Outcome

        class Failed(val failure: PreviewFailure) : Outcome
    }

    // Opening

    /** Opens a listed port; the opencode port, and any port not listed, is ignored. */
    private fun open(port: Int) {
        val openable = mutableState.value.ports.any { it.port == port && !it.isOpencode }
        if (openable) send(PortsEvent.OpenPage(port))
    }

    /**
     * Opens the manual entry when it is valid, or shows why not.
     *
     * While a listing runs and none has succeeded yet, the opencode port is not known, so a valid
     * entry is ignored rather than opened: the submit is dropped, not held, and the user submits
     * again once the list is shown (then [PortsState.manualProblem] says if it is the opencode
     * port). A refresh after a successful listing does not hold the entry up, because the last
     * known opencode port still applies.
     *
     * When no listing has ever succeeded and none runs (it failed, or the server cannot list its
     * ports), the opencode port is unknown to the picker and a manual entry of it is emitted; the
     * guard then is the connection manager's preview tunnel (P13.6), which refuses the opencode
     * server's port with `OpencodePortRefusedException`.
     */
    private fun openManual() {
        val port = synchronized(lock) {
            val current = mutableState.value
            when (val checked = check(current.manualText)) {
                is Checked.Port -> {
                    mutableState.value = current.copy(manualProblem = null)
                    checked.port.takeUnless { current.busy && !listedOnce }
                }
                is Checked.Problem -> {
                    mutableState.value = current.copy(manualProblem = checked.problem)
                    null
                }
            }
        }
        if (port != null) send(PortsEvent.OpenPage(port))
    }

    private fun send(event: PortsEvent) {
        eventChannel.trySend(event)
    }

    // Manual entry

    private sealed interface Checked {
        class Port(val port: Int) : Checked

        class Problem(val problem: ManualPortProblem) : Checked
    }

    /** While typing, an empty field is untouched rather than a problem. */
    private fun problemWhileTyping(text: String): ManualPortProblem? =
        when (val checked = check(text)) {
            is Checked.Port -> null
            is Checked.Problem -> checked.problem.takeUnless { it == ManualPortProblem.Empty }
        }

    /**
     * A whole number in 1..65535 written with ASCII digits, spaces around it allowed. A minus sign
     * followed by digits is a number out of range; anything else is not a number.
     */
    private fun check(text: String): Checked {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Checked.Problem(ManualPortProblem.Empty)
        val negative = trimmed.startsWith('-')
        val digits = if (negative) trimmed.substring(1) else trimmed
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return Checked.Problem(ManualPortProblem.NotANumber)
        if (negative) return Checked.Problem(ManualPortProblem.OutOfRange)
        val significant = digits.trimStart('0')
        if (significant.length > MAX_PORT_DIGITS) return Checked.Problem(ManualPortProblem.OutOfRange)
        val port = if (significant.isEmpty()) 0 else significant.toInt()
        if (port !in 1..MAX_PORT) return Checked.Problem(ManualPortProblem.OutOfRange)
        if (port in opencodePorts) return Checked.Problem(ManualPortProblem.OpencodePort)
        return Checked.Port(port)
    }

    private companion object {
        const val MAX_PORT = 65535
        const val MAX_PORT_DIGITS = 5
    }
}
