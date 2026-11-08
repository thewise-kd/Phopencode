package io.github.thewisekd.phopencode.feature.catalog.logic.contract

import io.github.thewisekd.phopencode.core.model.Skill

/**
 * The server's skills, `CatalogRoute.Skills` (P10.2 logic, P10.3 screen): the list with a search; a
 * row opens the skill's details.
 *
 * Where it comes from: [SkillCatalog], so the list is read once per connection; going back to the
 * screen reads it from there again ([SkillsIntent.Refresh]).
 *
 * @property skills Every skill read, in the server's order.
 * @property shown [skills] that [query] matches, in the same order; all of them when [query] is
 *   blank. The logic decides what matches.
 * @property refreshing A [SkillsIntent.Reload] runs while [skills] stays on screen.
 * @property error The list could not be read; [skills] keeps what was read before, if anything.
 */
data class SkillsState(
    val loading: Boolean = true,
    val skills: List<Skill> = emptyList(),
    val shown: List<Skill> = emptyList(),
    val query: String = "",
    val refreshing: Boolean = false,
    val error: CatalogFailure? = null,
) {
    /** Read, without an error and without skills: the server has none. */
    val isEmpty: Boolean
        get() = !loading && error == null && skills.isEmpty()

    /** A skill's path and text, and the search, are the user's. */
    override fun toString(): String =
        "SkillsState(loading=$loading, skills=${skills.size}, shown=${shown.size}, query=${query.length} chars, " +
            "refreshing=$refreshing, error=$error)"
}

sealed interface SkillsIntent {
    data class Search(val query: String) : SkillsIntent {
        override fun toString(): String = "Search(***)"
    }

    /** Reads through [SkillCatalog] again, kept list first; the graph sends it when the screen is shown again. */
    data object Refresh : SkillsIntent

    /** Reads the list from the server again, as a pull to refresh or a retry after [SkillsState.error]. */
    data object Reload : SkillsIntent

    /** A row tapped: sends [SkillsEvent.OpenSkill]. */
    data class OpenSkill(val skillId: String) : SkillsIntent

    data object Close : SkillsIntent
}

sealed interface SkillsEvent {
    data class OpenSkill(val skillId: String) : SkillsEvent

    data object Close : SkillsEvent
}

interface SkillsPresenter : Presenter<SkillsState, SkillsIntent, SkillsEvent>

/**
 * One skill, `CatalogRoute.SkillDetail` (P10.2 logic, P10.3 screen): its name, description, where its
 * file is, and its text as Markdown; it can start a new session with the skill on the first prompt.
 *
 * @property skillId From the route; never changes.
 * @property skill Null until found in [SkillCatalog].
 * @property sourcePath Where the skill's file is, as the screen shows it; the logic shortens
 *   [Skill.path] (P10.2). Null until loaded.
 * @property error The list could not be read, or [CatalogFailure.NotFound] when it has no skill with
 *   [skillId].
 */
data class SkillDetailState(
    val skillId: String,
    val loading: Boolean = true,
    val skill: Skill? = null,
    val sourcePath: String? = null,
    val error: CatalogFailure? = null,
) {
    override fun toString(): String =
        "SkillDetailState(skillId=$skillId, loading=$loading, skill=${skill?.let { "***" }}, " +
            "sourcePath=${sourcePath?.let { "***" }}, error=$error)"
}

sealed interface SkillDetailIntent {
    /** Reads again after [SkillDetailState.error]. */
    data object Retry : SkillDetailIntent

    /** Sends [SkillDetailEvent.StartSession] once the skill is loaded; nothing before. */
    data object UseInNewSession : SkillDetailIntent

    data object Close : SkillDetailIntent
}

sealed interface SkillDetailEvent {
    /** The graph opens `SessionsRoute.Create` with [skillId], which attaches it to the first prompt. */
    data class StartSession(val skillId: String) : SkillDetailEvent

    data object Close : SkillDetailEvent
}

interface SkillDetailPresenter : Presenter<SkillDetailState, SkillDetailIntent, SkillDetailEvent>
