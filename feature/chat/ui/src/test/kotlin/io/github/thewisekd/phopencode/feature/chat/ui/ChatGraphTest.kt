package io.github.thewisekd.phopencode.feature.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.navigation.Route
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.Presenter
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
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.BuiltInCommand
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.BuiltInNotice
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PalettePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PromptAttachment
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SessionAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState
import io.github.thewisekd.phopencode.feature.chat.ui.composer.COMPOSER_ACTION_TAG
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

/** A made-up folder, with a space and non-ASCII letters, as a route argument must keep them. */
private const val FOLDER = "/work/örnek proje"

/**
 * A presenter the test drives: its state is set by hand, its events are sent by hand, and every
 * intent it gets is recorded.
 */
private class Fake<S, I, E>(initial: S) : Presenter<S, I, E> {
    val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<S> = mutableState
    val eventChannel = Channel<E>(Channel.UNLIMITED)
    override val events: Flow<E> = eventChannel.receiveAsFlow()
    val intents: MutableList<I> = Collections.synchronizedList(mutableListOf<I>())

    override fun onIntent(intent: I) {
        intents += intent
    }
}

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

    private val statusState = MutableStateFlow(StatusState())

    private val status = object : StatusPresenter {
        override val state: StateFlow<StatusState> = statusState
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

    private val composerState = MutableStateFlow(ComposerState())
    private val composerIntents = Collections.synchronizedList(mutableListOf<ComposerIntent>())

    private val composer = object : ComposerPresenter {
        override val state: StateFlow<ComposerState> = composerState
        override val events: Flow<ComposerEvent> = composerEvents.receiveAsFlow()
        override fun onIntent(intent: ComposerIntent) {
            composerIntents += intent
        }
    }

    // One of each per route, so that each destination's events and intents stay apart.
    private val forms = HashMap<ChatRoute, Fake<FormsState, FormIntent, FormEvent>>()
    private val permissions = HashMap<ChatRoute, Fake<PermissionsState, PermissionIntent, Nothing>>()
    private val subagents = HashMap<ChatRoute, Fake<SubagentState, SubagentIntent, SubagentEvent>>()
    private val palettes = HashMap<ChatRoute, Fake<PaletteState, PaletteIntent, PaletteEvent>>()
    private val switchers = HashMap<ChatRoute, Fake<SwitcherState, SwitcherIntent, Nothing>>()
    private val reverts = HashMap<ChatRoute, Fake<RevertState, RevertIntent, RevertEvent>>()
    private val actions = HashMap<ChatRoute, Fake<ActionsState, ActionsIntent, ActionsEvent>>()

    private val opened = Collections.synchronizedList(mutableListOf<String>())
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
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
                    factory<FormPresenter> { (route: ChatRoute, _: CoroutineScope, session: ChatSession) ->
                        built += Built("form", route, session)
                        val fake = synchronized(forms) { forms.getOrPut(route) { Fake(FormsState()) } }
                        object : FormPresenter, Presenter<FormsState, FormIntent, FormEvent> by fake {}
                    }
                    factory<PermissionPresenter> { (route: ChatRoute, _: CoroutineScope, session: ChatSession) ->
                        built += Built("permission", route, session)
                        val fake = synchronized(permissions) { permissions.getOrPut(route) { Fake(PermissionsState()) } }
                        object : PermissionPresenter, Presenter<PermissionsState, PermissionIntent, Nothing> by fake {}
                    }
                    factory<SubagentPresenter> { (route: ChatRoute, _: CoroutineScope, session: ChatSession) ->
                        built += Built("subagent", route, session)
                        val fake = synchronized(subagents) { subagents.getOrPut(route) { Fake(SubagentState()) } }
                        object : SubagentPresenter, Presenter<SubagentState, SubagentIntent, SubagentEvent> by fake {}
                    }
                    factory<PalettePresenter> { (route: ChatRoute, _: CoroutineScope, session: ChatSession) ->
                        built += Built("palette", route, session)
                        val fake = synchronized(palettes) { palettes.getOrPut(route) { Fake(PaletteState()) } }
                        object : PalettePresenter, Presenter<PaletteState, PaletteIntent, PaletteEvent> by fake {}
                    }
                    factory<SwitcherPresenter> { (route: ChatRoute, _: CoroutineScope, session: ChatSession) ->
                        built += Built("switcher", route, session)
                        val fake = synchronized(switchers) { switchers.getOrPut(route) { Fake(SwitcherState()) } }
                        object : SwitcherPresenter, Presenter<SwitcherState, SwitcherIntent, Nothing> by fake {}
                    }
                    factory<RevertPresenter> { (route: ChatRoute, _: CoroutineScope, session: ChatSession) ->
                        built += Built("revert", route, session)
                        val fake = synchronized(reverts) { reverts.getOrPut(route) { Fake(RevertState()) } }
                        object : RevertPresenter, Presenter<RevertState, RevertIntent, RevertEvent> by fake {}
                    }
                    factory<ActionsPresenter> { (route: ChatRoute, _: CoroutineScope, session: ChatSession) ->
                        built += Built("actions", route, session)
                        val fake = synchronized(actions) { actions.getOrPut(route) { Fake(ActionsState()) } }
                        object : ActionsPresenter, Presenter<ActionsState, ActionsIntent, ActionsEvent> by fake {}
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
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    navController = rememberNavController()
                    NavHost(navController = navController, startDestination = ConnectRoute.ServerList) {
                        composable<ConnectRoute.ServerList> { Box(Modifier.fillMaxSize().testTag("start")) }
                        // Stand-ins for the files and git features' graphs, which this module cannot see.
                        composable<FilesRoute.Tree> { Box(Modifier.fillMaxSize().testTag("files")) }
                        composable<GitRoute.Status> { Box(Modifier.fillMaxSize().testTag("git")) }
                        composable<SessionsRoute.SessionList> { Box(Modifier.fillMaxSize().testTag("sessions")) }
                        chatGraph(navController)
                    }
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
            destination.hasRoute<ChatRoute.Subagents>() -> entry.toRoute<ChatRoute.Subagents>()
            destination.hasRoute<ChatRoute.ChildSession>() -> entry.toRoute<ChatRoute.ChildSession>()
            destination.hasRoute<ChatRoute.TurnDiff>() -> entry.toRoute<ChatRoute.TurnDiff>()
            destination.hasRoute<FilesRoute.Tree>() -> entry.toRoute<FilesRoute.Tree>()
            destination.hasRoute<GitRoute.Status>() -> entry.toRoute<GitRoute.Status>()
            destination.hasRoute<SessionsRoute.SessionList>() -> entry.toRoute<SessionsRoute.SessionList>()
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
            setOf(
                "status", "usage", "timeline", "reasoning", "tool", "composer",
                "form", "permission", "subagent", "palette", "switcher", "actions",
            ),
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

    private fun send(event: SubagentEvent, from: ChatRoute) {
        subagents.getValue(from).eventChannel.trySend(event)
        compose.waitForIdle()
    }

    @Test
    fun `the subagent badge opens the list, and a subagent opens its timeline`() {
        setNavHost()
        val chat = ChatRoute.Chat(SESSION)
        open(chat)

        send(SubagentEvent.OpenList(SessionId(SESSION)), from = chat)

        val list = ChatRoute.Subagents(SESSION)
        assertEquals(listOf(ConnectRoute.ServerList, chat, list), backStack())
        compose.onNodeWithTag("placeholder:$list").assertIsDisplayed()
        assertEquals(setOf("subagent"), built.filter { it.route == list }.map { it.type }.toSet())

        send(SubagentEvent.OpenChild(SessionId("ses_2")), from = list)

        val child = ChatRoute.ChildSession("ses_2")
        assertEquals(listOf(ConnectRoute.ServerList, chat, list, child), backStack())
        compose.onNodeWithTag("placeholder:$child").assertIsDisplayed()
        val childSession = sessions.single { it.first == child }.second
        val childBuilt = built.filter { it.route == child }
        assertEquals(
            setOf("status", "timeline", "reasoning", "tool", "subagent", "form", "permission"),
            childBuilt.map { it.type }.toSet(),
        )
        assertTrue(childBuilt.all { it.session === childSession })
    }

    @Test
    fun `leaving the subagent list goes back`() {
        setNavHost()
        open(ChatRoute.Chat(SESSION))
        val list = ChatRoute.Subagents(SESSION)
        open(list)

        send(SubagentEvent.Close, from = list)

        assertEquals(listOf(ConnectRoute.ServerList, ChatRoute.Chat(SESSION)), backStack())
    }

    @Test
    fun `a nested subagent opens its own list`() {
        setNavHost()
        val child = ChatRoute.ChildSession("ses_2")
        open(child)

        send(SubagentEvent.OpenList(SessionId("ses_2")), from = child)

        assertEquals(listOf(ConnectRoute.ServerList, child, ChatRoute.Subagents("ses_2")), backStack())
    }

    @Test
    fun `returning to a parent on the back stack goes back to it`() {
        setNavHost()
        val chat = ChatRoute.Chat(SESSION)
        open(chat)
        open(ChatRoute.Subagents(SESSION))
        val child = ChatRoute.ChildSession("ses_2")
        open(child)

        send(SubagentEvent.OpenParent(SessionId(SESSION), root = true), from = child)

        assertEquals(listOf(ConnectRoute.ServerList, chat), backStack())
    }

    @Test
    fun `returning to a parent not on the back stack opens it in place of the child`() {
        setNavHost()
        val grandchild = ChatRoute.ChildSession("ses_3")
        open(grandchild)

        send(SubagentEvent.OpenParent(SessionId("ses_2"), root = false), from = grandchild)

        val child = ChatRoute.ChildSession("ses_2")
        assertEquals(listOf(ConnectRoute.ServerList, child), backStack())

        send(SubagentEvent.OpenParent(SessionId(SESSION), root = true), from = child)

        assertEquals(listOf(ConnectRoute.ServerList, ChatRoute.Chat(SESSION)), backStack())
    }

    @Test
    fun `leaving a child session from its status goes back`() {
        setNavHost()
        open(ChatRoute.Chat(SESSION))
        open(ChatRoute.ChildSession("ses_2"))

        statusEvents.trySend(StatusEvent.Close)
        compose.waitForIdle()

        assertEquals(listOf(ConnectRoute.ServerList, ChatRoute.Chat(SESSION)), backStack())
    }

    @Test
    fun `the composer's draft goes to the palette as its query`() {
        setNavHost()
        val chat = ChatRoute.Chat(SESSION)
        open(chat)

        composerState.value = ComposerState(draft = "/rev")
        compose.waitForIdle()

        assertEquals(PaletteIntent.QueryChanged("/rev"), palettes.getValue(chat).intents.last())
    }

    @Test
    fun `a palette pick hands the new draft and the attachment to the composer`() {
        setNavHost()
        val chat = ChatRoute.Chat(SESSION)
        open(chat)
        val attachment = PromptAttachment.Agent("explore")

        palettes.getValue(chat).eventChannel.trySend(PaletteEvent.Attached("look at ", attachment))
        compose.waitForIdle()

        assertEquals(
            listOf(ComposerIntent.DraftChanged("look at "), ComposerIntent.Attach(attachment)),
            composerIntents.toList(),
        )
    }

    @Test
    fun `a command the server accepted clears the draft it was sent from and brings the timeline back`() {
        setNavHost()
        val chat = ChatRoute.Chat(SESSION)
        open(chat)
        composerState.value = ComposerState(draft = "/review now")

        palettes.getValue(chat).eventChannel.trySend(PaletteEvent.CommandSent(from = "/review now"))
        compose.waitForIdle()

        assertEquals(listOf<ComposerIntent>(ComposerIntent.DraftChanged("")), composerIntents.toList())
        assertEquals(listOf<TimelineIntent>(TimelineIntent.JumpToLatest), timelineIntents.toList())
    }

    @Test
    fun `a command the server accepted leaves a draft typed since, or any other, alone`() {
        setNavHost()
        val chat = ChatRoute.Chat(SESSION)
        open(chat)
        composerState.value = ComposerState(draft = "/review now and more")

        palettes.getValue(chat).eventChannel.trySend(PaletteEvent.CommandSent(from = "/review now"))
        compose.waitForIdle()

        assertTrue(composerIntents.isEmpty())
        assertEquals(listOf<TimelineIntent>(TimelineIntent.JumpToLatest), timelineIntents.toList())
    }

    // Slash words and built-ins

    /** A loaded, live chat whose composer holds [draft]. */
    private fun openReadyChat(draft: String): ChatRoute.Chat {
        setNavHost()
        val chat = ChatRoute.Chat(SESSION)
        open(chat)
        statusState.value = StatusState(ChatStatus.Idle)
        composerState.value = ComposerState(draft = draft, enabled = true)
        compose.waitForIdle()
        return chat
    }

    private fun runBuiltIn(chat: ChatRoute.Chat, command: BuiltInCommand, from: String, draft: String = "") {
        palettes.getValue(chat).eventChannel.trySend(PaletteEvent.RunBuiltIn(command, from, draft))
        compose.waitForIdle()
    }

    @Test
    fun `sending a draft that starts with a slash word hands it to the palette, not the composer`() {
        val chat = openReadyChat("/clear")

        compose.onNodeWithTag(COMPOSER_ACTION_TAG).performClick()
        compose.waitForIdle()

        assertTrue(PaletteIntent.Submit("/clear") in palettes.getValue(chat).intents)
        assertTrue(ComposerIntent.Send !in composerIntents)
    }

    @Test
    fun `sending any other draft goes to the composer`() {
        val chat = openReadyChat("say /clear")

        compose.onNodeWithTag(COMPOSER_ACTION_TAG).performClick()
        compose.waitForIdle()

        assertEquals(listOf<ComposerIntent>(ComposerIntent.Send), composerIntents.toList())
        assertTrue(palettes.getValue(chat).intents.none { it is PaletteIntent.Submit })
    }

    @Test
    fun `a slash word that is no command is sent as a prompt, only while the composer holds it`() {
        val chat = openReadyChat("/hello")

        palettes.getValue(chat).eventChannel.trySend(PaletteEvent.SendPrompt("/hello"))
        compose.waitForIdle()
        assertEquals(listOf<ComposerIntent>(ComposerIntent.Send), composerIntents.toList())

        composerState.value = ComposerState(draft = "/hello again", enabled = true)
        palettes.getValue(chat).eventChannel.trySend(PaletteEvent.SendPrompt("/hello"))
        compose.waitForIdle()
        assertEquals(listOf<ComposerIntent>(ComposerIntent.Send), composerIntents.toList())
    }

    @Test
    fun `a slash word that is a skill goes to the composer to send with the skill attached`() {
        val chat = openReadyChat("/granny-workflow do X")
        val skill = PromptAttachment.Skill("granny-workflow")

        compose.onNodeWithTag(COMPOSER_ACTION_TAG).performClick()
        compose.waitForIdle()
        assertTrue(PaletteIntent.Submit("/granny-workflow do X") in palettes.getValue(chat).intents)

        palettes.getValue(chat).eventChannel.trySend(PaletteEvent.SendSkill(skill, from = "/granny-workflow do X", text = "do X"))
        compose.waitForIdle()

        assertEquals(
            listOf<ComposerIntent>(ComposerIntent.SendSkill(from = "/granny-workflow do X", text = "do X", skill = skill)),
            composerIntents.toList(),
        )
    }

    @Test
    fun `a skill chip with nothing typed is sent by the composer`() {
        val chat = openReadyChat("")
        composerState.value = ComposerState(enabled = true, attachments = listOf(PromptAttachment.Skill("opencode")))
        compose.waitForIdle()

        compose.onNodeWithTag(COMPOSER_ACTION_TAG).performClick()
        compose.waitForIdle()

        assertEquals(listOf<ComposerIntent>(ComposerIntent.Send), composerIntents.toList())
        assertTrue(palettes.getValue(chat).intents.none { it is PaletteIntent.Submit })
    }

    @Test
    fun `compact and fork ask the actions for their confirmation and clear the draft`() {
        val chat = openReadyChat("/compact")
        val fake = actions.getValue(chat)
        fake.mutableState.value = ActionsState(enabled = true, directory = FOLDER)

        runBuiltIn(chat, BuiltInCommand.Compact, from = "/compact")
        composerState.value = ComposerState(draft = "/fork", enabled = true)
        runBuiltIn(chat, BuiltInCommand.Fork, from = "/fork")

        assertEquals(
            listOf<ActionsIntent>(
                ActionsIntent.Request(SessionAction.Compact),
                ActionsIntent.Request(SessionAction.Fork(null)),
            ),
            fake.intents.toList(),
        )
        assertEquals(
            listOf<ComposerIntent>(ComposerIntent.DraftChanged(""), ComposerIntent.DraftChanged("")),
            composerIntents.toList(),
        )
    }

    @Test
    fun `new creates a session and the chat switches to it`() {
        val chat = openReadyChat("/clear")
        val fake = actions.getValue(chat)
        fake.mutableState.value = ActionsState(enabled = true, directory = FOLDER)

        runBuiltIn(chat, BuiltInCommand.New, from = "/clear")

        assertEquals(listOf<ActionsIntent>(ActionsIntent.NewSession), fake.intents.toList())
        fake.eventChannel.trySend(ActionsEvent.OpenSession(SessionId("ses_new")))
        compose.waitForIdle()
        assertEquals(listOf(ConnectRoute.ServerList, ChatRoute.Chat("ses_new")), backStack())
    }

    @Test
    fun `a built-in that cannot run shows why and keeps the draft`() {
        val chat = openReadyChat("/compact")
        val fake = actions.getValue(chat)
        fake.mutableState.value = ActionsState(enabled = true, compacting = true)

        runBuiltIn(chat, BuiltInCommand.Compact, from = "/compact")
        runBuiltIn(chat, BuiltInCommand.Undo, from = "/compact")

        assertTrue(fake.intents.isEmpty())
        assertTrue(composerIntents.isEmpty())
        assertEquals(
            listOf<PaletteIntent>(
                PaletteIntent.ShowNotice(BuiltInNotice.Unavailable),
                PaletteIntent.ShowNotice(BuiltInNotice.NothingToUndo),
            ),
            palettes.getValue(chat).intents.filterIsInstance<PaletteIntent.ShowNotice>(),
        )
    }

    @Test
    fun `undo and redo open the turn's page`() {
        val chat = openReadyChat("/undo")
        val fake = actions.getValue(chat)
        fake.mutableState.value = ActionsState(enabled = true, undoTurn = MessageId("msg_2"), redoTurn = MessageId("msg_3"))

        runBuiltIn(chat, BuiltInCommand.Undo, from = "/undo")
        runBuiltIn(chat, BuiltInCommand.Redo, from = "/redo")

        assertEquals(
            listOf<ActionsIntent>(
                ActionsIntent.ShowTurnChanges(MessageId("msg_2")),
                ActionsIntent.ShowTurnChanges(MessageId("msg_3")),
            ),
            fake.intents.toList(),
        )
        // Only the first draft was still in the composer.
        assertEquals(listOf<ComposerIntent>(ComposerIntent.DraftChanged("")), composerIntents.toList())
    }

    @Test
    fun `models and agents open the switcher`() {
        val chat = openReadyChat("/models")
        switchers.getValue(chat).mutableState.value = SwitcherState(enabled = true)

        runBuiltIn(chat, BuiltInCommand.Models, from = "/models")
        runBuiltIn(chat, BuiltInCommand.Agents, from = "/agents")

        assertEquals(listOf<SwitcherIntent>(SwitcherIntent.Open, SwitcherIntent.Open), switchers.getValue(chat).intents.toList())
    }

    @Test
    fun `sessions goes back to the session list on the back stack`() {
        setNavHost()
        val list = SessionsRoute.SessionList(directory = FOLDER)
        open(list)
        val chat = ChatRoute.Chat(SESSION)
        open(chat)

        runBuiltIn(chat, BuiltInCommand.Sessions, from = "/sessions")

        assertEquals(listOf(ConnectRoute.ServerList, list), backStack())
        compose.onNodeWithTag("sessions").assertIsDisplayed()
    }

    @Test
    fun `sessions opens the folder's session list in place of the chat when none is on the back stack`() {
        val chat = openReadyChat("/resume")
        actions.getValue(chat).mutableState.value = ActionsState(directory = FOLDER)

        runBuiltIn(chat, BuiltInCommand.Sessions, from = "/resume")

        assertEquals(listOf(ConnectRoute.ServerList, SessionsRoute.SessionList(directory = FOLDER)), backStack())
    }

    @Test
    fun `a prompt's changes open the turn diff page, which goes back when done`() {
        setNavHost()
        val chat = ChatRoute.Chat(SESSION)
        open(chat)

        actions.getValue(chat).eventChannel.trySend(ActionsEvent.OpenTurnDiff(MessageId("msg_1")))
        compose.waitForIdle()

        val diff = ChatRoute.TurnDiff(SESSION, "msg_1")
        assertEquals(listOf(ConnectRoute.ServerList, chat, diff), backStack())
        compose.onNodeWithTag("placeholder:$diff").assertIsDisplayed()
        val revert = built.single { it.route == diff }
        assertEquals("revert", revert.type)
        assertTrue(revert.session === sessions.single { it.first == diff }.second)

        reverts.getValue(diff).eventChannel.trySend(RevertEvent.Reverted)
        compose.waitForIdle()

        assertEquals(listOf(ConnectRoute.ServerList, chat), backStack())
    }

    @Test
    fun `leaving the turn diff page goes back to the chat`() {
        setNavHost()
        open(ChatRoute.Chat(SESSION))
        val diff = ChatRoute.TurnDiff(SESSION, "msg_1")
        open(diff)

        reverts.getValue(diff).eventChannel.trySend(RevertEvent.Close)
        compose.waitForIdle()

        assertEquals(listOf(ConnectRoute.ServerList, ChatRoute.Chat(SESSION)), backStack())
    }

    @Test
    fun `a fork switches the chat to the new session`() {
        setNavHost()
        val chat = ChatRoute.Chat(SESSION)
        open(chat)

        actions.getValue(chat).eventChannel.trySend(ActionsEvent.OpenSession(SessionId("ses_fork")))
        compose.waitForIdle()

        val fork = ChatRoute.Chat("ses_fork")
        assertEquals(listOf(ConnectRoute.ServerList, fork), backStack())
        compose.onNodeWithTag("placeholder:$fork").assertIsDisplayed()
    }

    @Test
    fun `the chat opens the files of its session's folder on top of it`() {
        setNavHost()
        val chat = ChatRoute.Chat(SESSION)
        open(chat)

        actions.getValue(chat).eventChannel.trySend(ActionsEvent.OpenFiles(FOLDER))
        compose.waitForIdle()

        assertEquals(listOf(ConnectRoute.ServerList, chat, FilesRoute.Tree(FOLDER)), backStack())
        compose.onNodeWithTag("files").assertIsDisplayed()
    }

    @Test
    fun `the chat opens git for its session's folder on top of it`() {
        setNavHost()
        val chat = ChatRoute.Chat(SESSION)
        open(chat)

        actions.getValue(chat).eventChannel.trySend(ActionsEvent.OpenGit(FOLDER))
        compose.waitForIdle()

        assertEquals(listOf(ConnectRoute.ServerList, chat, GitRoute.Status(FOLDER)), backStack())
        compose.onNodeWithTag("git").assertIsDisplayed()
    }

    @Test
    fun `the chat's files button reaches the actions presenter`() {
        setNavHost()
        val chat = ChatRoute.Chat(SESSION)
        open(chat)
        // A loaded chat: the status no longer takes the screen.
        statusState.value = StatusState(ChatStatus.Idle)
        val fake = actions.getValue(chat)
        fake.mutableState.value = ActionsState(directory = FOLDER)
        compose.waitForIdle()

        compose.onNodeWithTag(ChatSlotTags.OPEN_FILES).performClick()
        compose.onNodeWithTag(ChatSlotTags.OPEN_GIT).performClick()
        compose.waitForIdle()

        assertEquals(listOf<ActionsIntent>(ActionsIntent.OpenFiles, ActionsIntent.OpenGit), fake.intents.toList())
    }

    @Test
    fun `an accepted compaction brings the timeline back to the newest item`() {
        setNavHost()
        val chat = ChatRoute.Chat(SESSION)
        open(chat)

        actions.getValue(chat).eventChannel.trySend(ActionsEvent.CompactionStarted)
        compose.waitForIdle()

        assertEquals(listOf<TimelineIntent>(TimelineIntent.JumpToLatest), timelineIntents.toList())
    }

    @Test
    fun `a form's web step opens outside the app, and only over http`() {
        setNavHost()
        val chat = ChatRoute.Chat(SESSION)
        open(chat)
        val page = "https://example.com/sign-in"

        forms.getValue(chat).eventChannel.trySend(FormEvent.OpenUrl(page))
        forms.getValue(chat).eventChannel.trySend(FormEvent.OpenUrl("javascript:alert(1)"))
        forms.getValue(chat).eventChannel.trySend(FormEvent.OpenUrl("file:///etc/hosts"))
        compose.waitForIdle()

        assertEquals(listOf(page), opened.toList())
    }
}
