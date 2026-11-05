package io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.Presenter

/**
 * The command palette: a list that opens over the composer while a `/` or `@` word is typed
 * (P7.8 builds it, P7.9 draws it).
 *
 * The trigger: the chat hands every composer draft over as [PaletteIntent.QueryChanged]. The word
 * the palette reads is the draft's last word, from its last whitespace to its end, and only when
 * the draft ends with it:
 * - `/` opens [PaletteTrigger.Slash], only as the draft's first word: commands
 *   (`CatalogApi.commands`) and skills (`CatalogApi.skills`).
 * - `@` opens [PaletteTrigger.At], anywhere: agents (`CatalogApi.agents`, hidden ones left out) and
 *   files (`FsApi.find(Location(session.directory), query)`).
 * The rest of the word is [PaletteState.query]; items match it case-insensitively, those that
 * start with it first. A trailing space, or no such word, closes the palette. Lists are asked
 * through `ChatSession.call`, for the session's folder, once per folder; files once per query.
 *
 * A pick ([PaletteIntent.Pick]):
 * - Skill, agent or file: becomes a [PromptAttachment], with [PaletteEvent.Attached] carrying the
 *   draft without the trigger word. The chat hands both to the composer.
 * - Command: runs at once, `MessageApi.command(sessionId, name, text)` through `ChatSession.call`,
 *   with what the draft holds after the command word as `text`; once the server accepts it,
 *   [PaletteEvent.CommandSent] carries the new, empty draft. A failed run keeps the draft and sets
 *   [PaletteState.error].
 *
 * @property trigger What opened the palette; null while closed.
 * @property query The word after the trigger. Prints masked.
 * @property items What matches, by kind: for `/` commands then skills, for `@` agents then files.
 * @property loading A list is being asked for.
 * @property running A command is on its way.
 * @property error The last list or command failed.
 */
data class PaletteState(
    val trigger: PaletteTrigger? = null,
    val query: String = "",
    val items: List<PaletteItem> = emptyList(),
    val loading: Boolean = false,
    val running: Boolean = false,
    val error: PaletteError? = null,
) {
    val open: Boolean
        get() = trigger != null

    override fun toString(): String =
        "PaletteState(trigger=$trigger, query=***, items=$items, loading=$loading, running=$running, error=$error)"
}

enum class PaletteTrigger {
    /** `/`: commands and skills. */
    Slash,

    /** `@`: agents and files. */
    At,
}

sealed interface PaletteItem {
    data class Command(val name: String, val description: String? = null) : PaletteItem

    data class Skill(val name: String, val description: String? = null) : PaletteItem

    /** @property id `Agent.id`, what the API takes; [name] is its display form. */
    data class Agent(val id: String, val name: String, val description: String? = null) : PaletteItem

    /** A path in the session's folder, as `FsApi.find` gave it. Prints masked. */
    data class File(val path: String, val directory: Boolean = false) : PaletteItem {
        override fun toString(): String = "File(***, directory=$directory)"
    }
}

enum class PaletteAction {
    /** Asking for a list. */
    Load,

    /** Running a command. */
    RunCommand,
}

data class PaletteError(val action: PaletteAction, val reason: ChatFailure)

sealed interface PaletteIntent {
    /** The composer's draft changed; the chat sends every change. */
    data class QueryChanged(val draft: String) : PaletteIntent {
        override fun toString(): String = "QueryChanged(***)"
    }

    data class Pick(val item: PaletteItem) : PaletteIntent

    /** Closes the palette until the trigger word changes. */
    data object Dismiss : PaletteIntent

    /** Asks again after a failed list. */
    data object Retry : PaletteIntent

    data object DismissError : PaletteIntent
}

sealed interface PaletteEvent {
    /**
     * A pick to attach. The chat sends [draft] to the composer as `ComposerIntent.DraftChanged` and
     * [attachment] as `ComposerIntent.Attach`.
     */
    data class Attached(val draft: String, val attachment: PromptAttachment) : PaletteEvent {
        override fun toString(): String = "Attached(draft=***, attachment=$attachment)"
    }

    /**
     * The server accepted a command. The chat sends [draft] to the composer as
     * `ComposerIntent.DraftChanged` and brings the timeline back to the newest item.
     */
    data class CommandSent(val draft: String) : PaletteEvent {
        override fun toString(): String = "CommandSent(***)"
    }
}

interface PalettePresenter : Presenter<PaletteState, PaletteIntent, PaletteEvent>
