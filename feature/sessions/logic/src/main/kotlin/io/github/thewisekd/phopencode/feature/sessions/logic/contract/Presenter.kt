package io.github.thewisekd.phopencode.feature.sessions.logic.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The logic of one screen. The screen draws [state] and sends [onIntent]; it decides nothing.
 *
 * The sessions feature's own copy of the shape the connect feature uses: a feature does not depend
 * on another feature.
 *
 * A presenter is built with the coroutine scope of its screen and does its work there, so it stops
 * when the screen goes away for good. Its constructor is fixed by the contract: the screen's logic
 * task fills the body in without changing it. Right after construction [state] holds the screen's
 * initial state; loading starts in the scope, never in the constructor.
 *
 * @param S Everything the screen shows. Immutable; printing it never shows a session title or text
 *   the user typed.
 * @param I What the user did.
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
