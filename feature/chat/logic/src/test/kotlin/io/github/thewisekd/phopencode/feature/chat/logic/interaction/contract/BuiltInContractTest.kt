package io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract

import io.github.thewisekd.phopencode.core.model.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val FOLDER = "/srv/work/project"

class BuiltInContractTest {
    private val ready = ActionsState(enabled = true, directory = FOLDER)
    private val switcherReady = SwitcherState(enabled = true)

    // Names

    @Test
    fun `built-ins carry the TUI's names and aliases`() {
        assertEquals(
            mapOf(
                "new" to listOf("clear"),
                "compact" to listOf("summarize"),
                "fork" to emptyList(),
                "undo" to emptyList(),
                "redo" to emptyList(),
                "models" to emptyList(),
                "agents" to emptyList(),
                "sessions" to listOf("resume", "continue"),
            ),
            BuiltInCommand.entries.associate { it.primary to it.aliases },
        )
    }

    @Test
    fun `a built-in is found by its name or an alias, in any case`() {
        assertEquals(BuiltInCommand.New, BuiltInCommand.named("new"))
        assertEquals(BuiltInCommand.New, BuiltInCommand.named("clear"))
        assertEquals(BuiltInCommand.New, BuiltInCommand.named("CLEAR"))
        assertEquals(BuiltInCommand.Compact, BuiltInCommand.named("Summarize"))
        assertEquals(BuiltInCommand.Sessions, BuiltInCommand.named("resume"))
        assertEquals(BuiltInCommand.Sessions, BuiltInCommand.named("continue"))
        assertNull(BuiltInCommand.named("cle"))
        assertNull(BuiltInCommand.named("share"))
        assertNull(BuiltInCommand.named(""))
    }

    // The slash word a draft starts with

    @Test
    fun `a slash word is the first word of the trimmed draft`() {
        assertEquals(SlashWord("clear", ""), SlashWord.of("/clear"))
        assertEquals(SlashWord("clear", ""), SlashWord.of("  /clear  "))
        assertEquals(SlashWord("review", "the last commit"), SlashWord.of("/review   the last commit "))
        assertEquals(SlashWord("review", "line one\nline two"), SlashWord.of("/review\nline one\nline two"))
        assertEquals(SlashWord("src/main.kt", "explain"), SlashWord.of("/src/main.kt explain"))
    }

    @Test
    fun `a draft that does not start with a named slash word has none`() {
        assertNull(SlashWord.of(""))
        assertNull(SlashWord.of("hello /clear"))
        assertNull(SlashWord.of("/"))
        assertNull(SlashWord.of("/ clear"))
        assertNull(SlashWord.of("@clear"))
    }

    @Test
    fun `the rest of the draft stays out of what prints`() {
        assertFalse("secret" in SlashWord("review", "secret plan").toString())
    }

    // What each one runs

    @Test
    fun `new creates a session in the folder, only when an action could start there`() {
        assertEquals(BuiltInRun.Actions(ActionsIntent.NewSession), BuiltInCommand.New.resolve(ready, switcherReady))
        val refused = BuiltInRun.Refused(BuiltInNotice.Unavailable)
        assertEquals(refused, BuiltInCommand.New.resolve(ready.copy(directory = null), switcherReady))
        assertEquals(refused, BuiltInCommand.New.resolve(ready.copy(enabled = false), switcherReady))
        assertEquals(refused, BuiltInCommand.New.resolve(ready.copy(running = SessionAction.Compact), switcherReady))
    }

    @Test
    fun `compact asks for the menu's compaction, under the menu's rules`() {
        assertEquals(
            BuiltInRun.Actions(ActionsIntent.Request(SessionAction.Compact)),
            BuiltInCommand.Compact.resolve(ready, switcherReady),
        )
        val refused = BuiltInRun.Refused(BuiltInNotice.Unavailable)
        assertEquals(refused, BuiltInCommand.Compact.resolve(ready.copy(compacting = true), switcherReady))
        assertEquals(refused, BuiltInCommand.Compact.resolve(ready.copy(running = SessionAction.Fork(null)), switcherReady))
        assertEquals(refused, BuiltInCommand.Compact.resolve(ActionsState(), switcherReady))
    }

    @Test
    fun `fork asks for the menu's fork of the whole session`() {
        assertEquals(
            BuiltInRun.Actions(ActionsIntent.Request(SessionAction.Fork(null))),
            BuiltInCommand.Fork.resolve(ready, switcherReady),
        )
        assertEquals(
            BuiltInRun.Refused(BuiltInNotice.Unavailable),
            BuiltInCommand.Fork.resolve(ready.copy(running = SessionAction.Compact), switcherReady),
        )
    }

    @Test
    fun `undo opens the latest turn's page, or says there is none`() {
        val turn = MessageId("msg_last")
        assertEquals(
            BuiltInRun.Actions(ActionsIntent.ShowTurnChanges(turn)),
            BuiltInCommand.Undo.resolve(ready.copy(undoTurn = turn), switcherReady),
        )
        assertEquals(BuiltInRun.Refused(BuiltInNotice.NothingToUndo), BuiltInCommand.Undo.resolve(ready, switcherReady))
        assertEquals(
            BuiltInRun.Refused(BuiltInNotice.Unavailable),
            BuiltInCommand.Undo.resolve(ActionsState(undoTurn = turn), switcherReady),
        )
    }

    @Test
    fun `redo opens the staged revert's page, or says nothing is staged`() {
        val turn = MessageId("msg_staged")
        assertEquals(
            BuiltInRun.Actions(ActionsIntent.ShowTurnChanges(turn)),
            BuiltInCommand.Redo.resolve(ready.copy(redoTurn = turn), switcherReady),
        )
        assertEquals(BuiltInRun.Refused(BuiltInNotice.NothingToRedo), BuiltInCommand.Redo.resolve(ready, switcherReady))
        assertEquals(
            BuiltInRun.Refused(BuiltInNotice.Unavailable),
            BuiltInCommand.Redo.resolve(ActionsState(redoTurn = turn), switcherReady),
        )
    }

    @Test
    fun `models and agents open the switcher while it is enabled`() {
        listOf(BuiltInCommand.Models, BuiltInCommand.Agents).forEach { command ->
            assertEquals(BuiltInRun.Switcher(SwitcherIntent.Open), command.resolve(ready, switcherReady))
            assertEquals(BuiltInRun.Refused(BuiltInNotice.Unavailable), command.resolve(ready, SwitcherState()))
        }
    }

    @Test
    fun `sessions always goes back to the list, of the session's folder`() {
        assertEquals(BuiltInRun.OpenSessions(FOLDER), BuiltInCommand.Sessions.resolve(ready, switcherReady))
        assertEquals(BuiltInRun.OpenSessions(null), BuiltInCommand.Sessions.resolve(ActionsState(), SwitcherState()))
        assertFalse(FOLDER in BuiltInRun.OpenSessions(FOLDER).toString())
    }
}
