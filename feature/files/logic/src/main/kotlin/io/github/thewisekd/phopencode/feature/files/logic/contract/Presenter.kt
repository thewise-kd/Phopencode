package io.github.thewisekd.phopencode.feature.files.logic.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The logic of one screen, or of one part of a screen. The screen draws [state] and sends
 * [onIntent]; it decides nothing.
 *
 * The files feature's own copy of the shape the other features use: a feature does not depend on
 * another feature.
 *
 * A presenter is built with the coroutine scope of its destination and does its work there, so it
 * stops when the destination goes away for good. Its constructor is fixed by the contract (P8.2):
 * the part's logic task fills the body in without changing it. Right after construction [state]
 * holds the initial state; nothing runs in the constructor. Work starts in the scope, and the first
 * thing a launched coroutine does is `yield()`, so that the screen's first frame always shows the
 * initial state.
 *
 * @param S Everything the part shows. Immutable; printing it never shows a path or file contents.
 * @param I What the user did, or what the graph forwards from another part.
 * @param E What happens once, outside the part: navigation, or a message to another part.
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
