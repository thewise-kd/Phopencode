package io.github.thewisekd.phopencode.feature.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.Route
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelinePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsagePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SESSION = "ses_1"

/**
 * How the graph builds a chat's presenters and turns their events into navigation. The start
 * destination stands in for the screen the chat was opened from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ChatGraphTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var navController: NavHostController

    private class FakeSession(override val sessionId: SessionId) : ChatSession {
        override val state: StateFlow<ChatSessionState> = MutableStateFlow(ChatSessionState())

        override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T = error("No server in this test")
    }

    /** What each factory was given: the presenter's type, the route and the session. */
    private data class Built(val type: String, val route: ChatRoute, val session: ChatSession)

    private val sessions = Collections.synchronizedList(mutableListOf<Pair<ChatRoute, ChatSession>>())
    private val built = Collections.synchronizedList(mutableListOf<Built>())

    private val statusEvents = Channel<StatusEvent>(Channel.UNLIMITED)
    private val barUsageEvents = Channel<UsageEvent>(Channel.UNLIMITED)
    private val pageUsageEvents = Channel<UsageEvent>(Channel.UNLIMITED)
    private val composerEvents = Channel<ComposerEvent>(Channel.UNLIMITED)
    private val timelineIntents = Collections.synchronizedList(mutableListOf<TimelineIntent>())

    private val status = object : StatusPresenter {
        override val state: StateFlow<StatusState> = MutableStateFlow(StatusState())
        override val events: Flow<StatusEvent> = statusEvents.receiveAsFlow()
        override fun onIntent(intent: StatusIntent) = Unit
    }

    private fun usage(events: Channel<UsageEvent>) = object : UsagePresenter {
        override val state: StateFlow<UsageState> = MutableStateFlow(UsageState())
        override val events: Flow<UsageEvent> = events.receiveAsFlow()
        override fun onIntent(intent: UsageIntent) = Unit
    }

    private val barUsage = usage(barUsageEvents)
    private val pageUsage = usage(pageUsageEvents)

    private val timeline = object : TimelinePresenter {
        override val state: StateFlow<TimelineState> = MutableStateFlow(TimelineState())
        override val events: Flow<Nothing> = emptyFlow()
        override fun onIntent(intent: TimelineIntent) {
            timelineIntents += intent
        }
    }

    private val reasoning = object : ReasoningPresenter {
        override val state: StateFlow<ReasoningState> = MutableStateFlow(ReasoningState())
        override val events: Flow<Nothing> = emptyFlow()
        override fun onIntent(intent: ReasoningIntent) = Unit
    }

    private val tool = object : ToolPresenter {
        override val state: StateFlow<ToolState> = MutableStateFlow(ToolState())
        override val events: Flow<Nothing> = emptyFlow()
        override fun onIntent(intent: ToolIntent) = Unit
    }

    private val composer = object : ComposerPresenter {
        override val state: StateFlow<ComposerState> = MutableStateFlow(ComposerState())
        override val events: Flow<ComposerEvent> = composerEvents.receiveAsFlow()
        override fun onIntent(intent: ComposerIntent) = Unit
    }

    @Before
    fun startDi() {
        startKoin {
            modules(
                module {
                    factory<ChatSession> { (route: ChatRoute, _: CoroutineScope) ->
                        FakeSession(SessionId(SESSION)).also { sessions += route to it }
                    }
                    factory<StatusPresenter> { (route: ChatRoute, _: CoroutineScope, session: ChatSession) ->
                        built += Built("status", route, session)
                        status
                    }
                    factory<UsagePresenter> { (route: ChatRoute, _: CoroutineScope, session: ChatSession) ->
                        built += Built("usage", route, session)
                        if (route is ChatRoute.TokenUsage) pageUsage else barUsage
                    }
                    factory<TimelinePresenter> { (route: ChatRoute, _: CoroutineScope, session: ChatSession) ->
                        built += Built("timeline", route, session)
                        timeline
                    }
                    factory<ReasoningPresenter> { (route: ChatRoute, _: CoroutineScope, session: ChatSession) ->
                        built += Built("reasoning", route, session)
                        reasoning
                    }
                    factory<ToolPresenter> { (route: ChatRoute, _: CoroutineScope, session: ChatSession) ->
                        built += Built("tool", route, session)
                        tool
                    }
                    factory<ComposerPresenter> { (route: ChatRoute, _: CoroutineScope, session: ChatSession) ->
                        built += Built("composer", route, session)
                        composer
                    }
                },
            )
        }
    }

    @After
    fun stopDi() {
        stopKoin()
    }

    private fun setNavHost() {
        compose.setContent {
            PhopencodeTheme {
                navController = rememberNavController()
                NavHost(navController = navController, startDestination = ConnectRoute.ServerList) {
                    composable<ConnectRoute.ServerList> { Box(Modifier.fillMaxSize().testTag("start")) }
                    chatGraph(navController)
                }
            }
        }
    }

    private fun open(route: Route) {
        compose.runOnIdle { navController.navigate(route) }
        compose.waitForIdle()
    }

    private fun routeOf(entry: NavBackStackEntry): Route? {
        val destination = entry.destination
        return when {
            destination.hasRoute<ConnectRoute.ServerList>() -> ConnectRoute.ServerList
            destination.hasRoute<ChatRoute.Chat>() -> entry.toRoute<ChatRoute.Chat>()
            destination.hasRoute<ChatRoute.TokenUsage>() -> entry.toRoute<ChatRoute.TokenUsage>()
            else -> null // The graph itself.
        }
    }

    private fun backStack(): List<Route> = compose.runOnIdle {
        navController.currentBackStack.value.mapNotNull(::routeOf)
    }

    @Test
    fun `a chat builds every presenter on one session of its own`() {
        setNavHost()
        val chat = ChatRoute.Chat(SESSION)

        open(chat)

        compose.onNodeWithTag("placeholder:$chat").assertIsDisplayed()
        assertEquals(listOf(chat as ChatRoute), sessions.map { it.first })
        val session = sessions.single().second
        assertEquals(
            setOf("status", "usage", "timeline", "reasoning", "tool", "composer"),
            built.map { it.type }.toSet(),
        )
        assertEquals(built.size, built.map { it.type }.toSet().size, "each presenter is built once")
        assertTrue(built.all { it.route == chat && it.session === session })
    }

    @Test
    fun `tapping the usage bar opens the usage page, on a session of its own`() {
        setNavHost()
        open(ChatRoute.Chat(SESSION))

        barUsageEvents.trySend(UsageEvent.OpenDetail)
        compose.waitForIdle()

        val page = ChatRoute.TokenUsage(SESSION)
        assertEquals(listOf(ConnectRoute.ServerList, ChatRoute.Chat(SESSION), page), backStack())
        compose.onNodeWithTag("placeholder:$page").assertIsDisplayed()
        assertEquals(listOf(ChatRoute.Chat(SESSION), page), sessions.map { it.first })
        assertTrue(built.single { it.route == page }.session === sessions.last().second)
    }

    @Test
    fun `leaving the usage page goes back to the chat`() {
        setNavHost()
        open(ChatRoute.Chat(SESSION))
        open(ChatRoute.TokenUsage(SESSION))

        pageUsageEvents.trySend(UsageEvent.Close)
        compose.waitForIdle()

        assertEquals(listOf(ConnectRoute.ServerList, ChatRoute.Chat(SESSION)), backStack())
    }

    @Test
    fun `closing from the status leaves the chat`() {
        setNavHost()
        open(ChatRoute.Chat(SESSION))

        statusEvents.trySend(StatusEvent.Close)
        compose.waitForIdle()

        assertEquals(listOf<Route>(ConnectRoute.ServerList), backStack())
    }

    @Test
    fun `a sent prompt brings the timeline back to the newest item`() {
        setNavHost()
        open(ChatRoute.Chat(SESSION))

        composerEvents.trySend(ComposerEvent.Sent)
        compose.waitForIdle()

        assertEquals(listOf<TimelineIntent>(TimelineIntent.JumpToLatest), timelineIntents.toList())
    }
}
