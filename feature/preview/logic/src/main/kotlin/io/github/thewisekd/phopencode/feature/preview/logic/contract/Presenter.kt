package io.github.thewisekd.phopencode.feature.preview.logic.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The logic of one screen. The screen draws [state] and sends [onIntent]; it decides nothing.
 *
 * The preview feature's own copy of the shape the other features use: a feature does not depend on
 * another feature.
 *
 * A presenter is built with the coroutine scope of its destination and does its work there, so it
 * stops when the destination goes away for good. Its constructor is fixed by the contract (P13.5):
 * the screen's logic task fills the body in without changing it. Right after construction [state]
 * holds the initial state; nothing runs in the constructor. Work starts in the scope, and the first
 * thing a launched coroutine does is `yield()`, so that the screen's first frame always shows the
 * initial state.
 *
 * @param S Everything the screen shows. Immutable; whatever in it could carry the bridge's secret or
 *   the page's own text (addresses, console messages) prints masked.
 * @param I What the user did, what the WebView reported, or what the graph forwards.
 * @param E What happens once, outside the screen: navigation, or a link handed to another app.
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
