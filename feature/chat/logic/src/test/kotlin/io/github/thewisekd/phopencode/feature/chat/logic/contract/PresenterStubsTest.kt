package io.github.thewisekd.phopencode.feature.chat.logic.contract

import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.feature.chat.logic.composer.DefaultComposerPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.reasoning.DefaultReasoningPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.status.DefaultStatusPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.timeline.DefaultTimelinePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.tool.DefaultToolPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.usage.DefaultUsagePresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Every presenter is built with the constructor the contract fixes, as `ChatModule` builds it, on
 * the chat's shared [ChatSession], and shows its initial state before anything has run. The logic
 * tasks fill the presenters in; these hold for the filled-in ones too.
 */
class PresenterStubsTest {
    private val sessionId = SessionId("ses_1")

    private fun TestScope.chat(): Pair<ChatConnection, ChatSession> {
        val connection = ChatConnection(backgroundScope)
        return connection to ActiveChatSession(sessionId, connection.manager, backgroundScope)
    }

    @Test
    fun `the timeline starts loading`() = runTest {
        val (_, session) = chat()
        val presenter: TimelinePresenter = DefaultTimelinePresenter(session = session, scope = backgroundScope)

        assertEquals(TimelineState(), presenter.state.value)
    }

    @Test
    fun `the composer starts empty and disabled`() = runTest {
        val (_, session) = chat()
        val presenter: ComposerPresenter = DefaultComposerPresenter(session = session, scope = backgroundScope)

        assertEquals(ComposerState(), presenter.state.value)
    }

    @Test
    fun `reasoning starts with no cards`() = runTest {
        val (_, session) = chat()
        val presenter: ReasoningPresenter = DefaultReasoningPresenter(session = session, scope = backgroundScope)

        assertEquals(ReasoningState(), presenter.state.value)
    }

    @Test
    fun `tools start with no cards`() = runTest {
        val (_, session) = chat()
        val presenter: ToolPresenter = DefaultToolPresenter(session = session, scope = backgroundScope)

        assertEquals(ToolState(), presenter.state.value)
    }

    @Test
    fun `usage starts loading`() = runTest {
        val (_, session) = chat()
        val presenter: UsagePresenter = DefaultUsagePresenter(session = session, scope = backgroundScope)

        assertEquals(UsageState(), presenter.state.value)
    }

    @Test
    fun `the status starts loading`() = runTest {
        val (connection, session) = chat()
        val presenter: StatusPresenter = DefaultStatusPresenter(
            session = session,
            connection = connection.manager,
            scope = backgroundScope,
        )

        assertEquals(StatusState(), presenter.state.value)
    }
}
