package io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract

import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.actions.DefaultActionsPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.form.DefaultFormPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.palette.DefaultPalettePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.permission.DefaultPermissionPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.revert.DefaultRevertPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.subagent.DefaultSubagentPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.switcher.DefaultSwitcherPresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

/**
 * Every interaction presenter is built with the constructor the contract fixes, `(route, scope,
 * session)` as `ChatModule` builds it, and shows its initial state before anything has run: no
 * server is asked in the constructor. The logic tasks fill the presenters in; these hold for the
 * filled-in ones too.
 */
class InteractionPresenterStubsTest {
    private val chat = ChatRoute.Chat(sessionId = "ses_1")

    /** A session that has asked nothing yet; any call fails the test. */
    private class QuietSession(override val sessionId: SessionId) : ChatSession {
        override val state: StateFlow<ChatSessionState> = MutableStateFlow(ChatSessionState())

        var calls = 0

        override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T {
            calls++
            error("Nothing may be asked in the constructor")
        }
    }

    private val session = QuietSession(SessionId("ses_1"))

    @Test
    fun `forms start with no card`() = runTest {
        val presenter: FormPresenter = DefaultFormPresenter(route = chat, scope = backgroundScope, session = session)

        assertEquals(FormsState(), presenter.state.value)
        assertEquals(0, session.calls)
    }

    @Test
    fun `permissions start with no card`() = runTest {
        val presenter: PermissionPresenter = DefaultPermissionPresenter(route = chat, scope = backgroundScope, session = session)

        assertEquals(PermissionsState(), presenter.state.value)
        assertEquals(0, session.calls)
    }

    @Test
    fun `subagents start loading, on the chat, the list and a child session`() = runTest {
        listOf(chat, ChatRoute.Subagents("ses_1"), ChatRoute.ChildSession("ses_1")).forEach { route ->
            val presenter: SubagentPresenter = DefaultSubagentPresenter(route = route, scope = backgroundScope, session = session)

            assertEquals(SubagentState(), presenter.state.value)
        }
        assertEquals(0, session.calls)
    }

    @Test
    fun `the palette starts closed`() = runTest {
        val presenter: PalettePresenter = DefaultPalettePresenter(route = chat, scope = backgroundScope, session = session)

        assertEquals(PaletteState(), presenter.state.value)
        assertEquals(0, session.calls)
    }

    @Test
    fun `the switcher starts closed and disabled`() = runTest {
        val presenter: SwitcherPresenter = DefaultSwitcherPresenter(route = chat, scope = backgroundScope, session = session)

        assertEquals(SwitcherState(), presenter.state.value)
        assertEquals(0, session.calls)
    }

    @Test
    fun `a turn's diff starts loading`() = runTest {
        val route = ChatRoute.TurnDiff(sessionId = "ses_1", messageId = "msg_1")
        val presenter: RevertPresenter = DefaultRevertPresenter(route = route, scope = backgroundScope, session = session)

        assertEquals(RevertState(), presenter.state.value)
        assertEquals(0, session.calls)
    }

    @Test
    fun `session actions start with the menu closed`() = runTest {
        val presenter: ActionsPresenter = DefaultActionsPresenter(route = chat, scope = backgroundScope, session = session)

        assertEquals(ActionsState(), presenter.state.value)
        assertEquals(0, session.calls)
    }
}
