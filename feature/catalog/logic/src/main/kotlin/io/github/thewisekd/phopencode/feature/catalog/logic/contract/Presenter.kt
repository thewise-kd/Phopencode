package io.github.thewisekd.phopencode.feature.catalog.logic.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The logic of one screen. The screen draws [state] and sends [onIntent]; it decides nothing.
 *
 * The catalog feature's own copy of the shape the other features use: a feature does not depend on
 * another feature.
 *
 * A presenter is built with the coroutine scope of its destination and does its work there, so it
 * stops when the destination goes away for good. Its constructor is fixed by the contract (P10.1):
 * the screen's logic task fills the body in without changing it. Right after construction [state]
 * holds the initial state; nothing runs in the constructor. Work starts in the scope, and the first
 * thing a launched coroutine does is `yield()`, so that the screen's first frame always shows the
 * initial state.
 *
 * @param S Everything the screen shows. Immutable; printing it never shows a skill's text or path, a
 *   description, a permission's resource, a search or an MCP or plugin error.
 * @param I What the user did, or what the graph forwards.
 * @param E What happens once, outside the screen: navigation.
 */
interface Presenter<S, I, E> {
    val state: StateFlow<S>

    /**
     * One-time events. Each one is delivered once, to one collector; an event sent while nobody
     * collects waits for the next collector (a buffered `Channel`, not a `SharedFlow`).
     */
    val events: Flow<E>

    /** Never blocks or suspends: work the intent starts runs in the presenter's scope. */
    fun onIntent(intent: I)
}
