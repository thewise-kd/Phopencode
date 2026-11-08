package io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract

import io.github.thewisekd.phopencode.core.model.MessageId

/**
 * The commands built into opencode's TUI that the app runs itself (P9.32), under the TUI's names.
 * The server lists only its own commands (`CatalogApi.commands`), so these never come from it; each
 * runs an action the chat already has:
 * - [New]: a new session in the session's folder, opened in place of this one
 *   ([ActionsIntent.NewSession]).
 * - [Compact] and [Fork]: the session menu's actions, with their confirmation
 *   ([ActionsIntent.Request]).
 * - [Undo]: the page of the latest turn not already staged for revert, whose revert still asks
 *   twice ([ActionsState.undoTurn]).
 * - [Redo]: the page of the staged revert, whose cancel drops it ([ActionsState.redoTurn]).
 * - [Models] and [Agents]: the agent and model switcher ([SwitcherIntent.Open]).
 * - [Sessions]: back to the session list.
 *
 * @property names The name, then its aliases, all lowercase and without the `/`.
 */
enum class BuiltInCommand(val names: List<String>) {
    New(listOf("new", "clear")),
    Compact(listOf("compact", "summarize")),
    Fork(listOf("fork")),
    Undo(listOf("undo")),
    Redo(listOf("redo")),
    Models(listOf("models")),
    Agents(listOf("agents")),
    Sessions(listOf("sessions", "resume", "continue")),
    ;

    /** The name the palette shows. */
    val primary: String
        get() = names.first()

    val aliases: List<String>
        get() = names.drop(1)

    /**
     * Whether this runs right now, given the states of the presenters it reaches; the same rules
     * as their buttons:
     * - [New]: [ActionsState.canAct] and a known folder.
     * - [Compact]: [ActionsState.canAct] and no compaction on its way, as the menu allows it.
     * - [Fork]: [ActionsState.canAct].
     * - [Undo] and [Redo]: [ActionsState.enabled] and a turn to open; without one, a notice.
     * - [Models] and [Agents]: [SwitcherState.enabled].
     * - [Sessions]: always.
     */
    fun resolve(actions: ActionsState, switcher: SwitcherState): BuiltInRun = when (this) {
        New -> if (actions.canAct && actions.directory != null) {
            BuiltInRun.Actions(ActionsIntent.NewSession)
        } else {
            BuiltInRun.Refused(BuiltInNotice.Unavailable)
        }
        Compact -> if (actions.canAct && !actions.compacting) {
            BuiltInRun.Actions(ActionsIntent.Request(SessionAction.Compact))
        } else {
            BuiltInRun.Refused(BuiltInNotice.Unavailable)
        }
        Fork -> if (actions.canAct) {
            BuiltInRun.Actions(ActionsIntent.Request(SessionAction.Fork(null)))
        } else {
            BuiltInRun.Refused(BuiltInNotice.Unavailable)
        }
        Undo -> turn(actions, actions.undoTurn, BuiltInNotice.NothingToUndo)
        Redo -> turn(actions, actions.redoTurn, BuiltInNotice.NothingToRedo)
        Models, Agents -> if (switcher.enabled) {
            BuiltInRun.Switcher(SwitcherIntent.Open)
        } else {
            BuiltInRun.Refused(BuiltInNotice.Unavailable)
        }
        Sessions -> BuiltInRun.OpenSessions(actions.directory)
    }

    private fun turn(
        actions: ActionsState,
        turn: MessageId?,
        none: BuiltInNotice,
    ): BuiltInRun = when {
        !actions.enabled -> BuiltInRun.Refused(BuiltInNotice.Unavailable)
        turn == null -> BuiltInRun.Refused(none)
        else -> BuiltInRun.Actions(ActionsIntent.ShowTurnChanges(turn))
    }

    companion object {
        /** The built-in [name] stands for, by its name or an alias, ignoring case. */
        fun named(name: String): BuiltInCommand? =
            entries.firstOrNull { command -> command.names.any { it.equals(name, ignoreCase = true) } }
    }
}

/** What running a built-in comes to; see [BuiltInCommand.resolve]. */
sealed interface BuiltInRun {
    /** Send [intent] to the actions presenter. */
    data class Actions(val intent: ActionsIntent) : BuiltInRun

    /** Send [intent] to the switcher presenter. */
    data class Switcher(val intent: SwitcherIntent) : BuiltInRun

    /** Go back to the session list, of [directory] when none is on the back stack. Prints masked. */
    data class OpenSessions(val directory: String?) : BuiltInRun {
        override fun toString(): String = "OpenSessions(${if (directory == null) "null" else "***"})"
    }

    /** Nothing runs; the palette shows [notice] ([PaletteIntent.ShowNotice]) and the draft stays. */
    data class Refused(val notice: BuiltInNotice) : BuiltInRun
}

/** Why a built-in did not run. */
enum class BuiltInNotice {
    /** The session is not ready for it, or another action runs, as its button would be disabled. */
    Unavailable,

    /** No turn of this session is left to undo. */
    NothingToUndo,

    /** No revert is staged. */
    NothingToRedo,
}

/**
 * The slash word a draft starts with, as sending reads it: the first word of the trimmed draft,
 * when it is `/` followed by a name.
 *
 * @property name The word without its `/`.
 * @property rest The rest of the draft, trimmed: a server command's text. Prints masked.
 */
data class SlashWord(val name: String, val rest: String) {
    override fun toString(): String = "SlashWord(name=$name, rest=***)"

    companion object {
        /** The slash word [draft] starts with, or null; leading blanks do not count. */
        fun of(draft: String): SlashWord? {
            val trimmed = draft.trim()
            if (!trimmed.startsWith('/')) return null
            val end = trimmed.indexOfFirst { it.isWhitespace() }.let { if (it < 0) trimmed.length else it }
            val name = trimmed.substring(1, end)
            if (name.isEmpty()) return null
            return SlashWord(name, trimmed.substring(end).trim())
        }
    }
}
