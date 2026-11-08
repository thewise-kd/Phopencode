package io.github.thewisekd.phopencode.feature.catalog.logic.skills

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * One skill: found by [skillId] in the list [skills] keeps, so the list screen's read is reused.
 *
 * - Loading starts once [scope] runs, after a `yield()`; nothing runs in the constructor.
 * - [SkillDetailState.sourcePath] is [io.github.thewisekd.phopencode.core.model.Skill.path] with a
 *   home folder written as `~` (see [skillSourcePath]).
 * - A list without [skillId] is [CatalogFailure.NotFound]. [SkillDetailIntent.Retry] after that
 *   asks the server again, since the kept list has already been searched; after any other failure
 *   it reads through the cache. A retry does nothing without a failure or while a read runs.
 * - [SkillDetailIntent.UseInNewSession] sends [SkillDetailEvent.StartSession] only while the skill
 *   is loaded.
 *
 * Nothing here logs: the skill's text and path are the user's.
 *
 * @param skillId From the route.
 * @param skills The app's skill cache.
 * @param scope The destination's scope; work started here stops with it.
 */
class DefaultSkillDetailPresenter(
    private val skillId: String,
    private val skills: SkillCatalog,
    private val scope: CoroutineScope,
) : SkillDetailPresenter {
    private val mutableState = MutableStateFlow(SkillDetailState(skillId = skillId))

    override val state: StateFlow<SkillDetailState> = mutableState.asStateFlow()

    private val eventChannel = Channel<SkillDetailEvent>(Channel.UNLIMITED)

    override val events: Flow<SkillDetailEvent> = eventChannel.receiveAsFlow()

    private val lock = Any()

    // Guarded by [lock].
    private var load: Job? = null
    private var loadToken: Any? = null

    init {
        synchronized(lock) { start(refresh = false) }
    }

    override fun onIntent(intent: SkillDetailIntent) {
        when (intent) {
            SkillDetailIntent.Retry -> synchronized(lock) {
                val error = mutableState.value.error
                if (error == null || load?.isActive == true) return
                mutableState.update { it.copy(loading = true, error = null) }
                start(refresh = error == CatalogFailure.NotFound)
            }
            SkillDetailIntent.UseInNewSession -> {
                val skill = mutableState.value.skill ?: return
                eventChannel.trySend(SkillDetailEvent.StartSession(skill.id))
            }
            SkillDetailIntent.Close -> eventChannel.trySend(SkillDetailEvent.Close)
        }
    }

    /**
     * Called holding [lock]. Only one load runs at a time (a retry waits for the last to end), and
     * each still writes the state only while it is the current one, checked under [lock].
     */
    private fun start(refresh: Boolean) {
        val token = Any()
        loadToken = token
        load = scope.launch {
            yield()
            val next: (SkillDetailState) -> SkillDetailState = try {
                val found = skills.skills(refresh).firstOrNull { it.id == skillId }
                if (found == null) {
                    { it.copy(loading = false, skill = null, sourcePath = null, error = CatalogFailure.NotFound) }
                } else {
                    { it.copy(loading = false, skill = found, sourcePath = skillSourcePath(found.path), error = null) }
                }
            } catch (e: ApiException) {
                { it.copy(loading = false, error = CatalogFailure.of(e.error)) }
            }
            synchronized(lock) {
                if (loadToken === token) mutableState.update(next)
            }
        }
    }
}
