package io.github.thewisekd.phopencode.feature.catalog.logic.skills

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailState
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
 * @param skillId From the route.
 * @param skills The app's skill cache.
 * @param scope The destination's scope; work started here stops with it.
 */
@Suppress("unused")
class DefaultSkillDetailPresenter(
    private val skillId: String,
    private val skills: SkillCatalog,
    private val scope: CoroutineScope,
) : SkillDetailPresenter {
    private val mutableState = MutableStateFlow(SkillDetailState(skillId = skillId))

    override val state: StateFlow<SkillDetailState> = mutableState.asStateFlow()

    override val events: Flow<SkillDetailEvent> = emptyFlow()

    override fun onIntent(intent: SkillDetailIntent) = Unit
}
