package io.github.thewisekd.phopencode.feature.catalog.logic.skills

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.model.Skill
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsState
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
 * The skill list with its search.
 *
 * - The list is read through [skills] once [scope] runs, after a `yield()`; nothing runs in the
 *   constructor.
 * - [SkillsIntent.Search] filters at once, on the list read: a skill matches when every
 *   whitespace-separated (Unicode spaces too) word of the query is in its ID, name or description, ignoring case. A
 *   blank query shows every skill. The order stays the server's.
 * - [SkillsIntent.Refresh] reads through the kept list, without showing it as loading. It does
 *   nothing while a reload runs; any other read running is replaced (its answer is not shown) by
 *   one for the connection standing now, which the catalog joins to that read when it is of the
 *   same connection, so a refresh after a reconnect never waits on the old connection.
 * - [SkillsIntent.Reload] asks the server again: as [SkillsState.refreshing] while a list is on
 *   screen, else as [SkillsState.loading]. It replaces a refresh that runs, and does nothing while
 *   another reload runs.
 * - A failure keeps the list read before and the search.
 *
 * Nothing here logs: the skills and the query are the user's.
 *
 * @param skills The app's skill cache.
 * @param scope The destination's scope; work started here stops with it.
 */
class DefaultSkillsPresenter(
    private val skills: SkillCatalog,
    private val scope: CoroutineScope,
) : SkillsPresenter {
    private val mutableState = MutableStateFlow(SkillsState())

    override val state: StateFlow<SkillsState> = mutableState.asStateFlow()

    private val eventChannel = Channel<SkillsEvent>(Channel.UNLIMITED)

    override val events: Flow<SkillsEvent> = eventChannel.receiveAsFlow()

    private val lock = Any()

    // Guarded by [lock].
    private var load: Job? = null
    private var loadIsReload = false
    private var loadToken: Any? = null

    init {
        synchronized(lock) { start(refresh = false) }
    }

    override fun onIntent(intent: SkillsIntent) {
        when (intent) {
            is SkillsIntent.Search -> mutableState.update { it.copy(query = intent.query, shown = it.skills.matching(intent.query)) }
            SkillsIntent.Refresh -> synchronized(lock) {
                if (load?.isActive == true && loadIsReload) return
                // Not cancelled: a read of the same connection is joined in the catalog without a
                // second server call, and one of an older connection ends on its own, unshown.
                start(refresh = false)
            }
            SkillsIntent.Reload -> synchronized(lock) {
                if (load?.isActive == true && loadIsReload) return
                mutableState.update {
                    if (it.skills.isEmpty()) it.copy(loading = true, error = null) else it.copy(refreshing = true, error = null)
                }
                start(refresh = true, cancelRunning = true)
            }
            is SkillsIntent.OpenSkill -> eventChannel.trySend(SkillsEvent.OpenSkill(intent.skillId))
            SkillsIntent.Close -> eventChannel.trySend(SkillsEvent.Close)
        }
    }

    /**
     * Called holding [lock]. The load writes the state only while it is still the current one,
     * checked under [lock]: one that a reload replaced may still answer after its cancel, and must
     * not overwrite the reload's state. The new token is taken before the running load is
     * cancelled, because a cancel can resume that load at once, on this thread, inside this lock.
     */
    private fun start(refresh: Boolean, cancelRunning: Boolean = false) {
        loadIsReload = refresh
        val token = Any()
        loadToken = token
        if (cancelRunning) load?.cancel()
        load = scope.launch {
            yield()
            val outcome = try {
                Result.success(skills.skills(refresh))
            } catch (e: ApiException) {
                Result.failure<List<Skill>>(e)
            }
            synchronized(lock) {
                if (loadToken === token) show(outcome)
            }
        }
    }

    /** Called holding [lock], by the current load. */
    private fun show(outcome: Result<List<Skill>>) {
        mutableState.update { current ->
            outcome.fold(
                onSuccess = { read ->
                    current.copy(loading = false, refreshing = false, error = null, skills = read, shown = read.matching(current.query))
                },
                onFailure = { e ->
                    current.copy(loading = false, refreshing = false, error = CatalogFailure.of((e as ApiException).error))
                },
            )
        }
    }
}

/** The skills [query] matches, as [DefaultSkillsPresenter] describes, in their order. */
internal fun List<Skill>.matching(query: String): List<Skill> {
    val words = query.searchWords()
    if (words.isEmpty()) return this
    return filter { skill ->
        words.all { word ->
            skill.id.contains(word, ignoreCase = true) ||
                skill.name.contains(word, ignoreCase = true) ||
                skill.description?.contains(word, ignoreCase = true) == true
        }
    }
}

/**
 * The words of a search, split at any [Char.isWhitespace]: Unicode spaces such as U+2003 and the
 * no-break space U+00A0 included, so a query of only those is blank.
 */
private fun String.searchWords(): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    for (char in this) {
        if (!char.isWhitespace()) {
            word.append(char)
        } else if (word.isNotEmpty()) {
            words += word.toString()
            word.clear()
        }
    }
    if (word.isNotEmpty()) words += word.toString()
    return words
}
