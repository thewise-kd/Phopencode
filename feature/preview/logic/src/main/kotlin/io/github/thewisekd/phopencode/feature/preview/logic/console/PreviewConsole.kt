package io.github.thewisekd.phopencode.feature.preview.logic.console

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleEntry
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The console of one open preview (P13.12): collects `console.*` messages, load errors, HTTP errors
 * and refused requests in a ring of at most [ConsoleState.MAX_ENTRIES] entries, each text cut at
 * [ConsoleState.MAX_TEXT_LENGTH] characters, with a level filter, clearing and the error count in
 * [ConsoleState.errorCount].
 *
 * The entries live in this object's memory only: nothing is logged, written to disk or sent
 * anywhere, and [toString] shows no entry. [close] drops them when the preview closes; after it,
 * every call is ignored and [state] stays empty. Safe to call from any thread.
 */
class PreviewConsole {
    private val mutableState = MutableStateFlow(ConsoleState())

    val state: StateFlow<ConsoleState> = mutableState.asStateFlow()

    /** Guards [closed] and every change of [mutableState], so nothing lands after [close]. */
    private val lock = Any()

    private var closed = false

    val isClosed: Boolean
        get() = synchronized(lock) { closed }

    /** Adds [entry], cut to the limits. */
    fun record(entry: ConsoleEntry) = change { it.recorded(entry) }

    /** A `console.*` message of the page. */
    fun message(level: ConsoleLevel, text: String, source: String?, line: Int?) =
        record(ConsoleEntries.message(level, text, source, line))

    /** The main frame or a sub-resource at [url] failed to load. */
    fun loadError(url: String, description: String?) = record(ConsoleEntries.loadError(url, description))

    /** The main frame or a sub-resource at [url] answered with HTTP [status] (100..599). */
    fun httpError(url: String, status: Int, reason: String?) =
        record(ConsoleEntries.httpError(url, status, reason))

    /** A request to [url], off the preview origin, was refused. */
    fun refused(url: String) = record(ConsoleEntries.refused(url))

    /** Shows only [level]; null shows all. */
    fun filter(level: ConsoleLevel?) = change { it.filteredBy(level) }

    /** Drops every entry; the filter stays. */
    fun clear() = change { it.cleared() }

    /** The preview closed: drops every entry and the filter, and ignores everything after. */
    fun close() {
        synchronized(lock) {
            closed = true
            mutableState.value = ConsoleState()
        }
    }

    override fun toString(): String = "PreviewConsole(${state.value}, closed=$isClosed)"

    private inline fun change(step: (ConsoleState) -> ConsoleState) {
        synchronized(lock) {
            if (closed) return
            mutableState.value = step(mutableState.value)
        }
    }
}
