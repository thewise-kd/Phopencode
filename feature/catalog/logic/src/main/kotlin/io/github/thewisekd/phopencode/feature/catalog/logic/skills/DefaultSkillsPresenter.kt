package io.github.thewisekd.phopencode.feature.catalog.logic.skills

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P10.1): shows the initial state and ignores intents. P10.2 fills it in
 * without changing the constructor; loading starts in [scope] after a
 * `yield()`.
 *
 * @param skills The app's skill cache.
 * @param scope The destination's scope; work started here stops with it.
 */
@Suppress("unused")
class DefaultSkillsPresenter(
    private val skills: SkillCatalog,
    private val scope: CoroutineScope,
) : SkillsPresenter {
    private val mutableState = MutableStateFlow(SkillsState())

    override val state: StateFlow<SkillsState> = mutableState.asStateFlow()

    override val events: Flow<SkillsEvent> = emptyFlow()

    override fun onIntent(intent: SkillsIntent) = Unit
}
