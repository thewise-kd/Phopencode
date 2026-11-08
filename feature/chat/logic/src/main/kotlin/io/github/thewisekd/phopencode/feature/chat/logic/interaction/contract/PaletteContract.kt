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
 *   (`CatalogApi.commands`), the app's built-ins ([BuiltInCommand]) and skills
 *   (`CatalogApi.skills`).
 * - `@` opens [PaletteTrigger.At], anywhere: agents (`CatalogApi.agents`, hidden ones left out) and
 *   files (`FsApi.find(Location(session.directory), query)`).
 * The rest of the word is [PaletteState.query]; items match it case-insensitively, those that
 * start with it first; a skill or an agent matches by its ID or its name. A built-in matches when its name or an alias starts with it, and shows from
 * the start, while the server's lists load or after they failed; one whose name is a server
 * command's is left out, as the server's command overrides it. A trailing space, or no such word, closes the palette. Lists are asked
 * through `ChatSession.call`, for the session's folder, once per folder; files once per query.
 *
 * A pick ([PaletteIntent.Pick]):
 * - Skill, agent or file: becomes a [PromptAttachment], with [PaletteEvent.Attached] carrying the
 *   draft without the trigger word. The chat hands both to the composer. A skill attaches by its
 *   ID, never its name.
 * - Command: runs at once, `MessageApi.command(sessionId, name, text)` through `ChatSession.call`,
 *   with what the draft holds after the command word as `text`; once the server accepts it,
 *   [PaletteEvent.CommandSent] names the draft it was sent from. A failed run, or one cut short,
 *   keeps the draft and sets [PaletteState.error].
 * - Built-in: under the same precedence as sending, below: a server command of the built-in's
 *   name runs instead, once the commands are known (waiting for them if they are still loading);
 *   else [PaletteEvent.RunBuiltIn], for the chat to run (see [BuiltInCommand.resolve]), also when
 *   the commands cannot be had.
 *
 * Sending ([PaletteIntent.Submit]): the chat hands over every draft that starts with a slash word
 * ([SlashWord]) instead of sending it. A server command of exactly that name runs as a pick does,
 * with the rest of the draft as its text; else a built-in of that name or alias, in any case,
 * becomes [PaletteEvent.RunBuiltIn]; else a skill whose ID is exactly that name (`/<skill>`, as in
 * opencode's own TUI) becomes [PaletteEvent.SendSkill], the rest of the draft sent as a prompt with
 * the skill attached; else [PaletteEvent.SendPrompt] hands it back to be sent as a prompt. So a
 * server command wins over a built-in, and both over a skill. Until the commands, then the skills,
 * are known for the session's folder they are asked first, sharing a request already on its way.
 * If the commands cannot be had, a built-in or a known skill still runs, and anything else keeps
 * the draft and sets [PaletteState.error]; if the skills cannot be had, the draft goes on as it
 * would with no skill of that ID: a built-in, else a prompt.
 *
 * @property trigger What opened the palette; null while closed.
 * @property query The word after the trigger. Prints masked.
 * @property items What matches, by kind: for `/` commands then skills, for `@` agents then files.
 * @property loading A list is being asked for.
 * @property running A command is on its way.
 * @property error The last list or command failed.
 * @property notice Why the last built-in did not run; cleared by the next draft or
 *   [PaletteIntent.DismissNotice]. Shows while the palette is closed too.
 */
data class PaletteState(
    val trigger: PaletteTrigger? = null,
    val query: String = "",
    val items: List<PaletteItem> = emptyList(),
    val loading: Boolean = false,
    val running: Boolean = false,
    val error: PaletteError? = null,
    val notice: BuiltInNotice? = null,
) {
    val open: Boolean
        get() = trigger != null

    override fun toString(): String =
        "PaletteState(trigger=$trigger, query=***, items=$items, loading=$loading, running=$running, error=$error, notice=$notice)"
}

enum class PaletteTrigger {
    /** `/`: commands, built-ins and skills. */
    Slash,

    /** `@`: agents and files. */
    At,
}

sealed interface PaletteItem {
    data class Command(val name: String, val description: String? = null) : PaletteItem

    /**
     * @property id `Skill.id`, what the API takes and what a pick attaches.
     * @property name `Skill.name`, its display form; the same as [id] for a user's skill, not for
     *   the server's own (id `opencode`, name `OpenCode`).
     */
    data class Skill(val id: String, val name: String = id, val description: String? = null) : PaletteItem

    /** One of the app's own commands; its texts come from the app. */
    data class BuiltIn(val command: BuiltInCommand) : PaletteItem

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

    /** Running a command, or finding out what a sent slash word is. */
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

    /**
     * The user sent [draft], which starts with a slash word: the chat hands it here instead of to
     * the composer. See [PaletteState] for what it becomes.
     */
    data class Submit(val draft: String) : PaletteIntent {
        override fun toString(): String = "Submit(***)"
    }

    /** A built-in did not run, for [notice]. */
    data class ShowNotice(val notice: BuiltInNotice) : PaletteIntent

    data object DismissNotice : PaletteIntent
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
     * The server accepted a command sent from the draft [from], exactly as it was sent. The chat
     * empties the composer's draft only if it still holds [from], so text typed since, or a draft
     * newer than the palette knew, is never overwritten; and brings the timeline back to the newest
     * item.
     */
    data class CommandSent(val from: String) : PaletteEvent {
        override fun toString(): String = "CommandSent(***)"
    }

    /**
     * Run [command] ([BuiltInCommand.resolve]). Only if it runs, and the composer's draft is still
     * [from], the chat sends [draft] to the composer as `ComposerIntent.DraftChanged`: the draft
     * without the slash word.
     */
    data class RunBuiltIn(val command: BuiltInCommand, val from: String, val draft: String) : PaletteEvent {
        override fun toString(): String = "RunBuiltIn(command=$command, from=***, draft=***)"
    }

    /**
     * The sent draft [from] starts with the ID of [skill]. The chat hands it to the composer as
     * `ComposerIntent.SendSkill`: [text], the draft after the slash word, may be empty, and goes as
     * a prompt with [skill] and the attachments the composer holds, only if the composer still
     * holds [from].
     */
    data class SendSkill(val skill: PromptAttachment.Skill, val from: String, val text: String) : PaletteEvent {
        override fun toString(): String = "SendSkill(skill=$skill, from=***, text=***)"
    }

    /** [draft] holds no command: the chat sends it as a prompt, if the composer still holds it. */
    data class SendPrompt(val draft: String) : PaletteEvent {
        override fun toString(): String = "SendPrompt(***)"
    }
}

interface PalettePresenter : Presenter<PaletteState, PaletteIntent, PaletteEvent>
