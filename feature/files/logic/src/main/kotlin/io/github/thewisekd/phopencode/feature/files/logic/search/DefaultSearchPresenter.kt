package io.github.thewisekd.phopencode.feature.files.logic.search

import io.github.thewisekd.phopencode.feature.files.logic.contract.FileSearch
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P8.2): shows the initial state and ignores intents. P8.14 fills it in
 * without changing the constructor; searches run in [scope], each starting with a `yield()`.
 *
 * @param directory The project's folder, from the route.
 * @param search Searches names and content.
 * @param limit The most results shown.
 */
@Suppress("unused")
class DefaultSearchPresenter(
    private val directory: String,
    private val search: FileSearch,
    private val scope: CoroutineScope,
    private val limit: Int = DEFAULT_LIMIT,
) : SearchPresenter {
    private val mutableState = MutableStateFlow(SearchState(directory = directory))

    override val state: StateFlow<SearchState> = mutableState.asStateFlow()

    override val events: Flow<SearchEvent> = emptyFlow()

    override fun onIntent(intent: SearchIntent) = Unit

    companion object {
        const val DEFAULT_LIMIT: Int = 200
    }
}
