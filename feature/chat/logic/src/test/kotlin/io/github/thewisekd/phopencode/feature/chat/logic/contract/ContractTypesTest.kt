package io.github.thewisekd.phopencode.feature.chat.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ContractTypesTest {
    // Assembled at runtime, so that the source holds no secret-shaped literal.
    private val secret = listOf("deploy", "with", "token", "42").joinToString("-")
    private val messageId = MessageId("msg_1")

    private fun assertMasked(value: Any, vararg hidden: String) {
        val printed = value.toString()
        hidden.forEach { assertFalse(it in printed, "$printed shows a hidden value") }
    }

    @Test
    fun `keys tell items apart and stay put across delivery`() {
        val keys = listOf(
            TimelineKey.message(messageId),
            TimelineKey.text(messageId, 0),
            TimelineKey.text(messageId, 1),
            TimelineKey.reasoning(messageId, 0),
            TimelineKey.tool(messageId, "call_1"),
            TimelineKey.message(MessageId("msg_2")),
        )
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys.size, keys.map { it.value }.toSet().size)
        // An inbox item becomes the message of the same ID: its key does not change.
        assertEquals(TimelineKey.message(messageId), TimelineKey.message(MessageId("msg_1")))
    }

    @Test
    fun `every timeline item has a key and hides what was typed or written`() {
        val items: List<TimelineItem> = listOf(
            TimelineItem.User(TimelineKey.message(messageId), messageId, text = secret, pending = PromptDelivery.Queue),
            TimelineItem.AssistantText(TimelineKey.text(messageId, 0), messageId, text = secret, streaming = true),
            TimelineItem.Reasoning(TimelineKey.reasoning(messageId, 1), messageId),
            TimelineItem.Tool(TimelineKey.tool(messageId, "call_1"), messageId, callId = "call_1", name = "bash"),
            TimelineItem.AssistantError(
                TimelineKey.message(messageId),
                messageId,
                StructuredError(type = "provider.transport", message = secret),
            ),
            TimelineItem.Shell(TimelineKey.message(messageId), messageId, command = secret, output = secret),
            TimelineItem.Compaction(TimelineKey.message(messageId), messageId, status = "completed", summary = secret),
            TimelineItem.Notice(TimelineKey.message(messageId), messageId, TimelineNotice.Synthetic(secret)),
            TimelineItem.Notice(TimelineKey.message(messageId), messageId, TimelineNotice.System(secret)),
            TimelineItem.TurnEnded(TimelineKey.message(messageId), messageId, TurnOutcome.Failed),
            TimelineItem.Unknown(TimelineKey.message(messageId), messageId, rawType = "future"),
        )
        items.forEach { assertMasked(it, secret) }
        assertEquals(PromptDelivery.Queue, (items[0] as TimelineItem.User).pending)
    }

    @Test
    fun `masked items still compare by content`() {
        val user = TimelineItem.User(TimelineKey.message(messageId), messageId, text = secret)
        assertEquals(user, user.copy())
        assertNotEquals(user, user.copy(text = "other"))
        assertNull(user.pending)
    }

    @Test
    fun `notices name what changed`() {
        val model = ModelRef("anthropic", "claude")
        assertEquals(model, TimelineNotice.ModelSwitched(model, previous = null).model)
        assertEquals("build", TimelineNotice.AgentSwitched("build", previous = "plan").agent)
        assertEquals("/srv/app", TimelineNotice.LocationSwitched("/srv/app").directory)
        assertEquals("review", TimelineNotice.SkillInvoked("review").name)
    }

    @Test
    fun `the timeline starts loading and following the latest`() {
        with(TimelineState()) {
            assertTrue(loading)
            assertTrue(items.isEmpty())
            assertFalse(hasOlder)
            assertFalse(loadingOlder)
            assertNull(olderError)
            assertTrue(followLatest)
            assertEquals(0, unseenCount)
            assertFalse(isEmpty)
        }
        assertTrue(TimelineState(loading = false).isEmpty)
    }

    @Test
    fun `the composer sends a draft only when it can`() {
        with(ComposerState()) {
            assertEquals("", draft)
            assertEquals(PromptDelivery.Steer, delivery)
            assertFalse(enabled)
            assertFalse(running)
            assertFalse(canSend)
            assertFalse(canStop)
            assertNull(error)
        }
        val ready = ComposerState(enabled = true, draft = "hello")
        assertTrue(ready.canSend)
        assertFalse(ready.copy(draft = "  ").canSend)
        assertFalse(ready.copy(sending = true).canSend)
        assertFalse(ready.copy(enabled = false).canSend)
        // While running a prompt is still sent, as steer or queue.
        assertTrue(ready.copy(running = true, delivery = PromptDelivery.Queue).canSend)
        assertTrue(ready.copy(running = true).canStop)
        assertFalse(ready.copy(running = true, stopping = true).canStop)
        assertFalse(ready.canStop)
    }

    @Test
    fun `drafts do not print`() {
        assertMasked(ComposerState(draft = secret), secret)
        assertMasked(ComposerIntent.DraftChanged(secret), secret)
        assertEquals(ComposerIntent.DraftChanged(secret), ComposerIntent.DraftChanged(secret))
    }

    @Test
    fun `a failed composer action names what failed`() {
        val failed = ComposerError(ComposerAction.Send, ChatFailure.Unreachable)
        assertEquals(ComposerAction.Send, failed.action)
        assertEquals(ChatFailure.Unreachable, failed.reason)
        assertEquals(listOf(ComposerAction.Send, ComposerAction.Stop), ComposerAction.entries)
    }

    @Test
    fun `reasoning cards print no thought`() {
        val card = ReasoningCardState(text = secret, streaming = true, duration = 3.seconds, expanded = false)
        assertMasked(card, secret)
        assertMasked(ReasoningState(cards = mapOf(TimelineKey.reasoning(messageId, 0) to card)), secret)
        assertTrue(ReasoningState().cards.isEmpty())
    }

    @Test
    fun `tool cards print no input or output`() {
        val card = ToolCardState(
            callId = "call_1",
            name = "bash",
            status = ToolCardStatus.Running,
            summary = ToolSummary.Shell(secret),
            input = secret,
            output = secret,
        )
        assertMasked(card, secret)
        assertMasked(ToolSummary.Search(pattern = secret, path = "/srv/app"), secret)
        assertTrue(card.expanded.isEmpty())
        assertEquals(listOf(ToolSection.Input, ToolSection.Output), ToolSection.entries)
        assertTrue(ToolState().cards.isEmpty())
    }

    @Test
    fun `tool summaries cover shell, edit, read, search and the rest`() {
        val summaries: List<ToolSummary> = listOf(
            ToolSummary.Shell("ls"),
            ToolSummary.Edit("src/App.kt", additions = 3, deletions = 1),
            ToolSummary.Read("README.md"),
            ToolSummary.Search("TODO", path = null),
            ToolSummary.Generic("webfetch"),
        )
        assertEquals(5, summaries.map { it::class }.toSet().size)
    }

    @Test
    fun `usage shows the context only with a limit`() {
        with(UsageState()) {
            assertTrue(loading)
            assertNull(usage)
            assertNull(contextLimit)
            assertNull(contextPercent)
            assertFalse(showsContext)
        }
        val usage = TokenUsage.of(input = 1000, output = 200)
        val state = UsageState(loading = false, usage = usage, contextLimit = 10_000, contextTokens = BigInteger.valueOf(1200), contextPercent = 12)
        assertTrue(state.showsContext)
        assertFalse(state.copy(contextPercent = null).showsContext)
    }

    @Test
    fun `only statuses that leave nothing to show block the chat`() {
        val blocking = listOf(
            ChatStatus.Loading,
            ChatStatus.NotConnected,
            ChatStatus.NotFound,
            ChatStatus.OtherServer,
            ChatStatus.Unavailable(ChatFailure.Unauthorized),
        )
        val open = listOf(
            ChatStatus.Idle,
            ChatStatus.Running,
            ChatStatus.Retrying(attempt = 2, nextAttemptAt = null),
            ChatStatus.Failed(error = null),
            ChatStatus.Disconnected,
        )
        blocking.forEach { assertTrue(StatusState(it).blocksChat, "$it") }
        open.forEach { assertFalse(StatusState(it).blocksChat, "$it") }
        assertEquals(ChatStatus.Loading, StatusState().status)
    }

    @Test
    fun `a retry prints no provider text`() {
        assertMasked(ChatStatus.Retrying(attempt = 1, nextAttemptAt = null, message = secret), secret)
        assertMasked(ChatStatus.Failed(StructuredError(type = "provider", message = secret)), secret)
    }

    @Test
    fun `the session source starts waiting with nothing loaded`() {
        with(ChatSessionState()) {
            assertEquals(ChatAvailability.Waiting, availability)
            assertNull(session)
            assertEquals(SyncPhase.Connecting, phase)
        }
    }

    @Test
    fun `api errors map to failures a screen can word`() {
        assertEquals(ChatFailure.Unauthorized, ChatFailure.of(ApiError.Unauthorized))
        assertEquals(ChatFailure.Rejected, ChatFailure.of(ApiError.Forbidden))
        assertEquals(ChatFailure.NotFound, ChatFailure.of(ApiError.NotFound))
        assertEquals(ChatFailure.Conflict, ChatFailure.of(ApiError.Conflict()))
        assertEquals(ChatFailure.Rejected, ChatFailure.of(ApiError.BadRequest()))
        assertEquals(ChatFailure.ServerFailed, ChatFailure.of(ApiError.Server(500)))
        assertEquals(ChatFailure.Unreachable, ChatFailure.of(ApiError.Network))
        assertEquals(ChatFailure.Unreachable, ChatFailure.of(ApiError.Timeout))
        assertEquals(ChatFailure.NotConnected, ChatFailure.of(ApiError.Closed))
        assertEquals(ChatFailure.Unknown, ChatFailure.of(ApiError.VersionTooOld("2.1")))
        assertEquals(ChatFailure.Unknown, ChatFailure.of(ApiError.MalformedResponse))
        assertEquals(ChatFailure.Unknown, ChatFailure.of(ApiError.Unexpected()))
    }

    @Test
    fun `a child session id is carried as is`() {
        val tool = TimelineItem.Tool(TimelineKey.tool(messageId, "call_2"), messageId, callId = "call_2", name = "subagent")
        assertEquals("call_2", tool.callId)
        assertEquals(SessionId("ses_child"), ToolCardState(
            callId = "call_2",
            name = "subagent",
            status = ToolCardStatus.Completed,
            summary = ToolSummary.Generic("subagent"),
            childSessionId = SessionId("ses_child"),
        ).childSessionId)
    }
}
