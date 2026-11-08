package io.github.thewisekd.phopencode.feature.chat.logic.palette

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.api.FileSearchType
import io.github.thewisekd.phopencode.core.api.FsApi
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.MessageApi
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.AgentMode
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.Skill
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import io.github.thewisekd.phopencode.core.testing.api.CommandRun
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.BuiltInCommand
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.BuiltInNotice
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteItem
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteTrigger
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PromptAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val DIR = "/work/project"
private const val OTHER_DIR = "/work/other"

private val MAIN = SessionId("ses_main")

/** Longer than any wait the presenter makes, shorter than the slow search below. */
private const val SETTLE_MS = 1_000L

/** Every built-in, as [DefaultPalettePresenterTest.names] prints them, in the order they list. */
private val ALL_BUILT_INS = listOf(
    "builtin:new", "builtin:compact", "builtin:fork", "builtin:undo", "builtin:redo",
    "builtin:models", "builtin:agents", "builtin:sessions",
)

/** A file search as the presenter asked for it. */
private data class Find(val location: Location, val query: String)

/**
 * A [ChatSession] the test drives: its state is set by hand, and [call] runs on [client] only while
 * [connected], as `ActiveChatSession` runs it only on the bound server.
 */
private class FakeChatSession(override val sessionId: SessionId) : ChatSession {
    val client = FakeOpencodeClient()

    val mutableState = MutableStateFlow(ChatSessionState())

    override val state: StateFlow<ChatSessionState> = mutableState

    @Volatile
    var connected = true

    /** When set, every skills list fails with it; the other catalog calls still work. */
    @Volatile
    var skillsFailure: ApiError? = null

    /** While set, every command run is cancelled, as a call cut short would be. */
    @Volatile
    var runsCancelled = false

    /** While set, every commands list is cancelled, as a call cut short would be. */
    @Volatile
    var commandsCancelled = false

    /** When set, every commands list fails with it; the other catalog calls still work. */
    @Volatile
    var commandsFailure: ApiError? = null

    /** How long a file search takes, by query; none by default. */
    val findDelays = mutableMapOf<String, Long>()

    val finds = mutableListOf<Find>()

    private val recording = object : OpencodeClient by client {
        override val messages: MessageApi = object : MessageApi by client.messages {
            override suspend fun command(sessionId: SessionId, name: String, text: String) {
                if (runsCancelled) throw CancellationException("The call was cut short.")
                client.messages.command(sessionId, name, text)
            }
        }

        override val catalog: CatalogApi = object : CatalogApi by client.catalog {
            override suspend fun skills(location: Location?): List<Skill> {
                skillsFailure?.let { throw ApiException(it) }
                return client.catalog.skills(location)
            }

            override suspend fun commands(location: Location?): List<Command> {
                if (commandsCancelled) throw CancellationException("The call was cut short.")
                commandsFailure?.let { throw ApiException(it) }
                return client.catalog.commands(location)
            }
        }

        override val fs: FsApi = object : FsApi {
            override suspend fun find(location: Location, query: String, type: FileSearchType?, limit: Int?): List<FileEntry> {
                finds += Find(location, query)
                findDelays[query]?.let { delay(it) }
                return client.fs.find(location, query, type, limit)
            }
        }
    }

    override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T {
        if (!connected) throw ApiException(ApiError.Closed)
        return block(recording)
    }

    fun live(directory: String? = DIR) {
        mutableState.value = ChatSessionState(
            availability = ChatAvailability.Live,
            session = SessionState(Session(id = sessionId, directory = directory)),
            phase = SyncPhase.Live,
        )
    }

    /** The catalog calls that reached the server, oldest first. */
    fun catalogCalls(): List<String> = client.control.calls.filter { it.startsWith("catalog.") }
}

/** A skill as `GET /api/skill` lists it: a user's skill has its name as its id, a built-in one not. */
private fun skill(name: String, description: String? = null, id: String = name) =
    Skill(id = id, name = name, description = description, path = "/skills/$id.md", content = "body of $name")

private fun agent(id: String, hidden: Boolean = false) =
    Agent(id = id, name = id.replaceFirstChar { it.uppercase() }, description = "$id agent", mode = AgentMode.All, hidden = hidden)

private fun file(path: String, type: FileEntryType = FileEntryType.File) = FileEntry(path = path, type = type)

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPalettePresenterTest {
    private fun FakeOpencodeClient.seedCatalog() {
        sessions.seed(Session(id = MAIN, directory = DIR))
        catalog.setCommands(listOf(Command("review", "Review changes"), Command("init"), Command("preview")), Location(DIR))
        catalog.setSkills(listOf(skill("release", "Cut a release"), skill("Refactor")), Location(DIR))
        catalog.setAgents(listOf(agent("build"), agent("plan"), agent("title", hidden = true), agent("explore")), Location(DIR))
        fs.seed(
            Location(DIR),
            listOf(
                file("src/main/Builder.kt"),
                file("build.gradle.kts"),
                file("docs", FileEntryType.Directory),
                file("README.md"),
            ),
        )
    }

    private fun TestScope.setup(
        arrange: (FakeChatSession) -> Unit = {},
    ): Triple<FakeChatSession, DefaultPalettePresenter, MutableList<PaletteEvent>> {
        val chat = FakeChatSession(MAIN)
        chat.client.seedCatalog()
        chat.live()
        arrange(chat)
        val presenter = DefaultPalettePresenter(route = ChatRoute.Chat(MAIN.value), scope = backgroundScope, session = chat)
        val events = mutableListOf<PaletteEvent>()
        backgroundScope.launch { presenter.events.collect { events += it } }
        runCurrent()
        return Triple(chat, presenter, events)
    }

    /**
     * Lets the presenter's work run, waits included: `advanceUntilIdle` does not wait for work in
     * `backgroundScope`, where the presenter runs.
     */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_MS)
        runCurrent()
    }

    private fun TestScope.type(presenter: DefaultPalettePresenter, draft: String) {
        presenter.onIntent(PaletteIntent.QueryChanged(draft))
        settle()
    }

    private fun PaletteState.names(): List<String> = items.map {
        when (it) {
            is PaletteItem.Command -> "/${it.name}"
            is PaletteItem.Skill -> "skill:${it.id}"
            is PaletteItem.Agent -> "@${it.id}"
            is PaletteItem.File -> "file:${it.path}"
            is PaletteItem.BuiltIn -> "builtin:${it.command.primary}"
        }
    }

    // Opening and closing

    @Test
    fun `nothing is asked in the constructor or before a trigger is typed`() = runTest {
        val chat = FakeChatSession(MAIN)
        chat.client.seedCatalog()
        chat.live()
        val presenter = DefaultPalettePresenter(route = ChatRoute.Chat(MAIN.value), scope = backgroundScope, session = chat)
        presenter.onIntent(PaletteIntent.QueryChanged("hello"))

        assertEquals(PaletteState(), presenter.state.value)
        settle()

        assertFalse(presenter.state.value.open)
        assertTrue(chat.client.control.calls.isEmpty())
        assertTrue(chat.finds.isEmpty())
    }

    @Test
    fun `a slash as the first word lists commands, built-ins, then skills for the session's folder`() = runTest {
        val (chat, presenter) = setup()

        type(presenter, "/")

        val state = presenter.state.value
        assertEquals(PaletteTrigger.Slash, state.trigger)
        assertEquals("", state.query)
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(listOf("/review", "/init", "/preview") + ALL_BUILT_INS + listOf("skill:release", "skill:Refactor"), state.names())
        assertEquals(PaletteItem.Command("review", "Review changes"), state.items.first())
        assertEquals(PaletteItem.BuiltIn(BuiltInCommand.New), state.items[3])
        assertEquals(PaletteItem.Skill(id = "release", name = "release", description = "Cut a release"), state.items[3 + ALL_BUILT_INS.size])
        assertEquals(setOf("catalog.commands", "catalog.skills"), chat.catalogCalls().toSet())
        assertTrue(chat.finds.isEmpty())
    }

    @Test
    fun `a slash after leading blanks still counts as the first word`() = runTest {
        val (_, presenter) = setup()

        type(presenter, "  /in")

        assertEquals(PaletteTrigger.Slash, presenter.state.value.trigger)
        assertEquals(listOf("/init"), presenter.state.value.names())
    }

    @Test
    fun `a slash later in the draft opens nothing`() = runTest {
        val (chat, presenter) = setup()

        type(presenter, "fix it /rev")

        assertFalse(presenter.state.value.open)
        assertTrue(chat.client.control.calls.isEmpty())
    }

    @Test
    fun `items match case-insensitively, those that start with the query first`() = runTest {
        val (_, presenter) = setup()

        type(presenter, "/RE")

        assertEquals("RE", presenter.state.value.query)
        // Commands, built-ins (by a name or an alias that starts with it), then skills; within each
        // kind, prefix matches lead.
        assertEquals(
            listOf("/review", "/preview", "builtin:redo", "builtin:sessions", "skill:release", "skill:Refactor"),
            presenter.state.value.names(),
        )
    }

    @Test
    fun `the lists are asked once per folder, not per keystroke`() = runTest {
        val (chat, presenter) = setup()

        type(presenter, "/")
        type(presenter, "/r")
        type(presenter, "/re")
        type(presenter, "")
        type(presenter, "/i")

        assertEquals(2, chat.catalogCalls().size)
        assertEquals(listOf("/init", "/review", "/preview"), presenter.state.value.names())
    }

    @Test
    fun `another folder asks again`() = runTest {
        val (chat, presenter) = setup()
        chat.client.catalog.setCommands(listOf(Command("deploy")), Location(OTHER_DIR))
        type(presenter, "/")

        chat.live(OTHER_DIR)
        settle()

        assertEquals(listOf("/deploy") + ALL_BUILT_INS, presenter.state.value.names())
        assertEquals(4, chat.catalogCalls().size)
    }

    @Test
    fun `an at word anywhere lists visible agents then files`() = runTest {
        val (chat, presenter) = setup()

        type(presenter, "ask @bu")

        val state = presenter.state.value
        assertEquals(PaletteTrigger.At, state.trigger)
        assertEquals("bu", state.query)
        assertEquals(listOf("@build", "file:src/main/Builder.kt", "file:build.gradle.kts"), state.names())
        assertEquals(PaletteItem.Agent("build", "Build", "build agent"), state.items.first())
        assertEquals(listOf(Find(Location(DIR), "bu")), chat.finds)
        assertEquals(listOf("catalog.agents"), chat.catalogCalls())
    }

    @Test
    fun `hidden agents never show`() = runTest {
        val (_, presenter) = setup()

        type(presenter, "@t")

        assertTrue("@title" !in presenter.state.value.names())
    }

    @Test
    fun `a folder found shows as one`() = runTest {
        val (_, presenter) = setup()

        type(presenter, "@doc")

        assertEquals(listOf<PaletteItem>(PaletteItem.File("docs", directory = true)), presenter.state.value.items)
    }

    @Test
    fun `a trailing space or no trigger closes the palette`() = runTest {
        val (_, presenter) = setup()
        type(presenter, "/rev")
        assertTrue(presenter.state.value.open)

        type(presenter, "/rev ")

        assertFalse(presenter.state.value.open)
        assertTrue(presenter.state.value.items.isEmpty())

        type(presenter, "@bu")
        assertTrue(presenter.state.value.open)
        type(presenter, "@bu and")
        assertFalse(presenter.state.value.open)
    }

    @Test
    fun `a session with no folder lists the default catalog and searches no files`() = runTest {
        val (chat, presenter) = setup { it.live(directory = null) }
        chat.client.catalog.setAgents(listOf(agent("plan")))

        type(presenter, "@")

        assertEquals(listOf("@plan"), presenter.state.value.names())
        assertTrue(chat.finds.isEmpty())
    }

    @Test
    fun `dismiss closes until the trigger word changes`() = runTest {
        val (_, presenter) = setup()
        type(presenter, "/re")

        presenter.onIntent(PaletteIntent.Dismiss)
        runCurrent()
        assertFalse(presenter.state.value.open)

        type(presenter, "/re")
        assertFalse(presenter.state.value.open)

        type(presenter, "/rev")
        assertTrue(presenter.state.value.open)
        assertEquals(listOf("/review", "/preview"), presenter.state.value.names())
    }

    // Debounce and stale answers

    @Test
    fun `typing fast searches files only for the last query`() = runTest {
        val (chat, presenter) = setup()

        presenter.onIntent(PaletteIntent.QueryChanged("@b"))
        runCurrent()
        presenter.onIntent(PaletteIntent.QueryChanged("@bu"))
        runCurrent()
        presenter.onIntent(PaletteIntent.QueryChanged("@bui"))
        settle()

        assertEquals(listOf(Find(Location(DIR), "bui")), chat.finds)
        assertEquals(listOf("@build", "file:src/main/Builder.kt", "file:build.gradle.kts"), presenter.state.value.names())
    }

    @Test
    fun `an old answer never replaces a newer one`() = runTest {
        val (chat, presenter) = setup { it.findDelays["read"] = 60_000 }
        val seen = mutableListOf<PaletteState>()
        backgroundScope.launch { presenter.state.collect { seen += it } }

        presenter.onIntent(PaletteIntent.QueryChanged("@read"))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(Find(Location(DIR), "read")), chat.finds)
        assertTrue(presenter.state.value.loading)

        presenter.onIntent(PaletteIntent.QueryChanged("@bu"))
        settle()

        assertEquals(listOf("@build", "file:src/main/Builder.kt", "file:build.gradle.kts"), presenter.state.value.names())
        assertFalse(presenter.state.value.loading)
        assertTrue(seen.none { state -> state.items.any { it == PaletteItem.File("README.md") } })
    }

    @Test
    fun `a filter on a list already known shows at once`() = runTest {
        val (_, presenter) = setup()
        type(presenter, "/")

        presenter.onIntent(PaletteIntent.QueryChanged("/in"))
        runCurrent()

        assertEquals(listOf("/init"), presenter.state.value.names())
    }

    // Failures

    @Test
    fun `a failed list shows the error, and retry asks again`() = runTest {
        val (chat, presenter) = setup { it.client.control.failNext(ApiError.Network) }

        type(presenter, "/")

        assertEquals(PaletteError(PaletteAction.Load, ChatFailure.Unreachable), presenter.state.value.error)
        assertFalse(presenter.state.value.loading)
        assertTrue(presenter.state.value.open)

        presenter.onIntent(PaletteIntent.Retry)
        settle()

        assertNull(presenter.state.value.error)
        assertEquals(5 + ALL_BUILT_INS.size, presenter.state.value.items.size)
        assertTrue(chat.catalogCalls().size >= 3)
    }

    @Test
    fun `without the bound server nothing is asked and the palette says so`() = runTest {
        val (chat, presenter) = setup { it.connected = false }

        type(presenter, "@bu")

        assertEquals(PaletteError(PaletteAction.Load, ChatFailure.NotConnected), presenter.state.value.error)
        assertTrue(chat.client.control.calls.isEmpty())
        assertTrue(chat.finds.isEmpty())
    }

    @Test
    fun `dismiss error clears it`() = runTest {
        val (_, presenter) = setup { it.client.control.failNext(ApiError.Network) }
        type(presenter, "/")

        presenter.onIntent(PaletteIntent.DismissError)
        runCurrent()

        assertNull(presenter.state.value.error)
    }

    // Picks

    @Test
    fun `a skill pick becomes an attachment and the draft loses the trigger word`() = runTest {
        val (_, presenter, events) = setup()
        type(presenter, "/rel")

        presenter.onIntent(PaletteIntent.Pick(PaletteItem.Skill(id = "release", name = "release", description = "Cut a release")))
        settle()

        assertEquals(listOf<PaletteEvent>(PaletteEvent.Attached("", PromptAttachment.Skill("release"))), events)
        assertFalse(presenter.state.value.open)
    }

    @Test
    fun `an agent pick attaches its id`() = runTest {
        val (_, presenter, events) = setup()
        type(presenter, "ask @pl")

        presenter.onIntent(PaletteIntent.Pick(PaletteItem.Agent("plan", "Plan", "plan agent")))
        settle()

        assertEquals(listOf<PaletteEvent>(PaletteEvent.Attached("ask ", PromptAttachment.Agent("plan"))), events)
    }

    @Test
    fun `a file pick attaches its path`() = runTest {
        val (_, presenter, events) = setup()
        type(presenter, "look at @READ")

        presenter.onIntent(PaletteIntent.Pick(PaletteItem.File("README.md")))
        settle()

        assertEquals(listOf<PaletteEvent>(PaletteEvent.Attached("look at ", PromptAttachment.File("README.md"))), events)
    }

    @Test
    fun `a pick that is not shown does nothing`() = runTest {
        val (chat, presenter, events) = setup()
        type(presenter, "/rev")

        presenter.onIntent(PaletteIntent.Pick(PaletteItem.Skill("ghost")))
        presenter.onIntent(PaletteIntent.Pick(PaletteItem.Command("init")))
        settle()

        assertTrue(events.isEmpty())
        assertTrue(chat.client.messages.commandsRun.isEmpty())
    }

    @Test
    fun `a pick right after the draft lost its trigger word runs nothing`() = runTest {
        val (chat, presenter, events) = setup { it.client.catalog.setCommands(listOf(Command("format")), Location(DIR)) }
        type(presenter, "/fo")
        assertEquals(listOf("/format", "builtin:fork"), presenter.state.value.names())

        // The items still shown belong to `/fo`; the pick comes before the palette catches up.
        presenter.onIntent(PaletteIntent.QueryChanged("hello"))
        presenter.onIntent(PaletteIntent.Pick(PaletteItem.Command("format")))
        settle()

        assertTrue(events.isEmpty())
        assertTrue(chat.client.messages.commandsRun.isEmpty())
        assertFalse(presenter.state.value.open)
    }

    @Test
    fun `a pick right after the folder changed attaches nothing from the old one`() = runTest {
        val (chat, presenter, events) = setup()
        chat.client.fs.seed(Location(OTHER_DIR), listOf(file("README.md")))
        type(presenter, "@READ")
        assertEquals(listOf<PaletteItem>(PaletteItem.File("README.md")), presenter.state.value.items)

        // The items shown are the old folder's; the pick comes before the palette catches up.
        chat.live(OTHER_DIR)
        presenter.onIntent(PaletteIntent.Pick(PaletteItem.File("README.md")))
        settle()

        assertTrue(events.isEmpty())
        assertEquals(listOf(Find(Location(DIR), "READ"), Find(Location(OTHER_DIR), "READ")), chat.finds)
    }

    @Test
    fun `events wait for a collector and none is dropped`() = runTest {
        val chat = FakeChatSession(MAIN)
        chat.client.seedCatalog()
        chat.live()
        val presenter = DefaultPalettePresenter(route = ChatRoute.Chat(MAIN.value), scope = backgroundScope, session = chat)
        runCurrent()
        val picks = 200

        repeat(picks) { index ->
            type(presenter, "note $index @READ")
            presenter.onIntent(PaletteIntent.Pick(PaletteItem.File("README.md")))
        }
        settle()

        val events = presenter.events.take(picks).toList()
        assertEquals(List(picks) { PaletteEvent.Attached("note $it ", PromptAttachment.File("README.md")) }, events)
    }

    @Test
    fun `a pick right after the trigger word changed attaches nothing`() = runTest {
        val (_, presenter, events) = setup()
        type(presenter, "@READ")
        assertEquals(listOf<PaletteItem>(PaletteItem.File("README.md")), presenter.state.value.items)

        presenter.onIntent(PaletteIntent.QueryChanged("see @bu"))
        presenter.onIntent(PaletteIntent.Pick(PaletteItem.File("README.md")))
        settle()

        assertTrue(events.isEmpty())
        assertEquals("bu", presenter.state.value.query)
    }

    @Test
    fun `text typed while a command runs is kept`() = runTest {
        val (chat, presenter, events) = setup()
        type(presenter, "/rev")
        chat.client.control.stall()

        presenter.onIntent(PaletteIntent.Pick(PaletteItem.Command("review", "Review changes")))
        runCurrent()
        presenter.onIntent(PaletteIntent.QueryChanged("/rev and more"))
        runCurrent()

        chat.client.control.resume()
        settle()

        assertEquals(listOf(CommandRun(MAIN, "review", "")), chat.client.messages.commandsRun)
        assertEquals(listOf<PaletteEvent>(PaletteEvent.CommandSent(from = "/rev")), events)
        assertFalse(presenter.state.value.running)
    }

    @Test
    fun `a command pick runs it and empties the draft once the server accepts it`() = runTest {
        val (chat, presenter, events) = setup()
        type(presenter, "/rev")
        chat.client.control.stall()

        presenter.onIntent(PaletteIntent.Pick(PaletteItem.Command("review", "Review changes")))
        runCurrent()

        assertTrue(presenter.state.value.running)
        assertTrue(events.isEmpty())
        // A second pick while one runs is ignored.
        presenter.onIntent(PaletteIntent.Pick(PaletteItem.Command("review", "Review changes")))

        chat.client.control.resume()
        settle()

        assertEquals(listOf(CommandRun(MAIN, "review", "")), chat.client.messages.commandsRun)
        assertEquals(listOf<PaletteEvent>(PaletteEvent.CommandSent(from = "/rev")), events)
        assertFalse(presenter.state.value.running)
        assertFalse(presenter.state.value.open)
    }

    @Test
    fun `a failed command keeps the draft and shows the error`() = runTest {
        val (chat, presenter, events) = setup()
        type(presenter, "/rev")
        chat.client.control.failNext(ApiError.Server(500))

        presenter.onIntent(PaletteIntent.Pick(PaletteItem.Command("review", "Review changes")))
        settle()

        assertTrue(events.isEmpty())
        assertEquals(PaletteError(PaletteAction.RunCommand, ChatFailure.ServerFailed), presenter.state.value.error)
        assertFalse(presenter.state.value.running)
        assertTrue(presenter.state.value.open)
        assertEquals(listOf("/review", "/preview"), presenter.state.value.names())
    }

    @Test
    fun `a command needs the bound server`() = runTest {
        val (chat, presenter, events) = setup()
        type(presenter, "/rev")
        chat.connected = false

        presenter.onIntent(PaletteIntent.Pick(PaletteItem.Command("review", "Review changes")))
        settle()

        assertTrue(events.isEmpty())
        assertTrue(chat.client.messages.commandsRun.isEmpty())
        assertEquals(PaletteError(PaletteAction.RunCommand, ChatFailure.NotConnected), presenter.state.value.error)
    }


    // Built-ins

    @Test
    fun `a built-in matches by an alias that starts with the query`() = runTest {
        val (_, presenter) = setup()

        type(presenter, "/cle")
        assertEquals(listOf("builtin:new"), presenter.state.value.names())

        type(presenter, "/SUM")
        assertEquals(listOf("builtin:compact"), presenter.state.value.names())

        type(presenter, "/con")
        assertEquals(listOf("builtin:sessions"), presenter.state.value.names())

        // Only from the start: "continue" holds "in", but /in is the server's /init alone.
        type(presenter, "/in")
        assertEquals(listOf("/init"), presenter.state.value.names())
    }

    @Test
    fun `built-ins show while the server's lists load, and after they failed`() = runTest {
        val (chat, presenter) = setup()
        chat.client.control.stall()

        type(presenter, "/u")

        assertTrue(presenter.state.value.loading)
        assertEquals(listOf("builtin:undo"), presenter.state.value.names())

        chat.client.control.failNext(ApiError.Network)
        chat.client.control.resume()
        settle()

        assertEquals(PaletteError(PaletteAction.Load, ChatFailure.Unreachable), presenter.state.value.error)
        assertEquals(listOf("builtin:undo"), presenter.state.value.names())
    }

    @Test
    fun `a server command of a built-in's name hides that built-in`() = runTest {
        val (_, presenter) = setup {
            it.client.catalog.setCommands(listOf(Command("new", "The server's new"), Command("clear")), Location(DIR))
        }

        type(presenter, "/")

        assertEquals(
            listOf("/new", "/clear") + ALL_BUILT_INS.drop(1) + listOf("skill:release", "skill:Refactor"),
            presenter.state.value.names(),
        )
    }

    @Test
    fun `a built-in pick asks the chat to run it, with the draft it leaves`() = runTest {
        val (chat, presenter, events) = setup()
        type(presenter, "/cle")

        presenter.onIntent(PaletteIntent.Pick(PaletteItem.BuiltIn(BuiltInCommand.New)))
        settle()

        assertEquals(listOf<PaletteEvent>(PaletteEvent.RunBuiltIn(BuiltInCommand.New, from = "/cle", draft = "")), events)
        assertTrue(chat.client.messages.commandsRun.isEmpty())
        assertEquals(listOf("catalog.commands", "catalog.skills"), chat.catalogCalls().sorted())
    }

    @Test
    fun `a built-in not shown is not picked`() = runTest {
        val (_, presenter, events) = setup()
        type(presenter, "/cle")

        presenter.onIntent(PaletteIntent.Pick(PaletteItem.BuiltIn(BuiltInCommand.Undo)))
        settle()

        assertTrue(events.isEmpty())
    }

    // A cancelled commands request

    @Test
    fun `a cancelled commands request fails the list, and the next one asks again and completes`() = runTest {
        val (chat, presenter) = setup { it.commandsCancelled = true }

        // Virtual time: a list that waited on the cut-short request forever would still be loading.
        type(presenter, "/")

        assertFalse(presenter.state.value.loading)
        assertEquals(PaletteError(PaletteAction.Load, ChatFailure.Unknown), presenter.state.value.error)
        assertEquals(ALL_BUILT_INS + listOf("skill:release", "skill:Refactor"), presenter.state.value.names())

        chat.commandsCancelled = false
        presenter.onIntent(PaletteIntent.Retry)
        settle()

        assertNull(presenter.state.value.error)
        assertEquals(5 + ALL_BUILT_INS.size, presenter.state.value.items.size)
    }

    @Test
    fun `a send waiting on a cancelled commands request does not hang`() = runTest {
        val (chat, presenter, events) = setup { it.commandsCancelled = true }

        presenter.onIntent(PaletteIntent.Submit("/review now"))
        settle()

        // Like a failed fetch: no built-in of that name, so the draft stays and the failure shows.
        assertFalse(presenter.state.value.running)
        assertTrue(events.isEmpty())
        assertEquals(PaletteError(PaletteAction.RunCommand, ChatFailure.Unknown), presenter.state.value.error)

        // A built-in still runs.
        presenter.onIntent(PaletteIntent.Submit("/clear"))
        settle()
        assertEquals(listOf<PaletteEvent>(PaletteEvent.RunBuiltIn(BuiltInCommand.New, from = "/clear", draft = "")), events)

        // The next send asks again, and the server's command runs.
        chat.commandsCancelled = false
        presenter.onIntent(PaletteIntent.Submit("/review now"))
        settle()
        assertEquals(listOf(CommandRun(MAIN, "review", "now")), chat.client.messages.commandsRun)
        assertFalse(presenter.state.value.running)
    }

    @Test
    fun `a waiter that stops waiting leaves the shared request to the others`() = runTest {
        val (chat, presenter, events) = setup()
        chat.client.control.stall()
        type(presenter, "/re")
        // The list stops waiting: the word is gone.
        type(presenter, "hello")

        presenter.onIntent(PaletteIntent.Submit("/review now"))
        runCurrent()
        chat.client.control.resume()
        settle()

        assertEquals(listOf(CommandRun(MAIN, "review", "now")), chat.client.messages.commandsRun)
        assertEquals(1, chat.catalogCalls().count { it == "catalog.commands" })
        assertEquals(1, events.size)
    }

    // Picking a built-in while the commands load

    @Test
    fun `a built-in picked while the commands load waits for them, and the server's command of its name wins`() = runTest {
        val (chat, presenter, events) = setup {
            it.client.catalog.setCommands(listOf(Command("new", "The server's new")), Location(DIR))
        }
        chat.client.control.stall()
        type(presenter, "/ne")
        assertTrue(presenter.state.value.loading)
        assertEquals(listOf("builtin:new"), presenter.state.value.names())

        presenter.onIntent(PaletteIntent.Pick(PaletteItem.BuiltIn(BuiltInCommand.New)))
        runCurrent()
        assertTrue(presenter.state.value.running)
        assertTrue(events.isEmpty())

        chat.client.control.resume()
        settle()

        assertEquals(listOf(CommandRun(MAIN, "new", "")), chat.client.messages.commandsRun)
        assertEquals(listOf<PaletteEvent>(PaletteEvent.CommandSent(from = "/ne")), events)
        // The pick shares the palette's own request for the commands.
        assertEquals(1, chat.catalogCalls().count { it == "catalog.commands" })
        assertFalse(presenter.state.value.running)
    }

    @Test
    fun `a built-in picked while the commands load runs once they hold no command of its name`() = runTest {
        val (chat, presenter, events) = setup()
        chat.client.control.stall()
        type(presenter, "/ne")

        presenter.onIntent(PaletteIntent.Pick(PaletteItem.BuiltIn(BuiltInCommand.New)))
        runCurrent()
        assertTrue(events.isEmpty())

        chat.client.control.resume()
        settle()

        assertEquals(listOf<PaletteEvent>(PaletteEvent.RunBuiltIn(BuiltInCommand.New, from = "/ne", draft = "")), events)
        assertTrue(chat.client.messages.commandsRun.isEmpty())
        assertEquals(1, chat.catalogCalls().count { it == "catalog.commands" })
        assertFalse(presenter.state.value.running)
    }

    @Test
    fun `a built-in picked while the commands load still runs when they fail`() = runTest {
        val (chat, presenter, events) = setup {
            it.client.catalog.setCommands(listOf(Command("new", "The server's new")), Location(DIR))
        }
        chat.client.control.stall()
        type(presenter, "/ne")

        presenter.onIntent(PaletteIntent.Pick(PaletteItem.BuiltIn(BuiltInCommand.New)))
        runCurrent()
        chat.client.control.failNext(ApiError.Network)
        chat.client.control.resume()
        settle()

        assertEquals(listOf<PaletteEvent>(PaletteEvent.RunBuiltIn(BuiltInCommand.New, from = "/ne", draft = "")), events)
        assertTrue(chat.client.messages.commandsRun.isEmpty())
        assertFalse(presenter.state.value.running)
        // The list's failure shows as the list's, not as a failed command.
        assertEquals(PaletteError(PaletteAction.Load, ChatFailure.Unreachable), presenter.state.value.error)
    }

    // Sending a slash word

    @Test
    fun `sending a built-in's name or alias runs it, in any case, with the rest of the draft left`() = runTest {
        val (chat, presenter, events) = setup()

        presenter.onIntent(PaletteIntent.Submit("/clear"))
        settle()
        presenter.onIntent(PaletteIntent.Submit("  /Compact  "))
        settle()
        presenter.onIntent(PaletteIntent.Submit("/RESUME later on"))
        settle()

        assertEquals(
            listOf<PaletteEvent>(
                PaletteEvent.RunBuiltIn(BuiltInCommand.New, from = "/clear", draft = ""),
                PaletteEvent.RunBuiltIn(BuiltInCommand.Compact, from = "  /Compact  ", draft = ""),
                PaletteEvent.RunBuiltIn(BuiltInCommand.Sessions, from = "/RESUME later on", draft = "later on"),
            ),
            events,
        )
        assertTrue(chat.client.messages.commandsRun.isEmpty())
        assertFalse(presenter.state.value.running)
    }

    @Test
    fun `sending a server command's exact name runs it with the rest as its text`() = runTest {
        val (chat, presenter, events) = setup()
        type(presenter, "/review the last commit")

        presenter.onIntent(PaletteIntent.Submit("/review the last commit"))
        settle()

        assertEquals(listOf(CommandRun(MAIN, "review", "the last commit")), chat.client.messages.commandsRun)
        assertEquals(listOf<PaletteEvent>(PaletteEvent.CommandSent(from = "/review the last commit")), events)
    }

    @Test
    fun `a server command wins over a built-in of the same name, the built-in keeps its other names`() = runTest {
        val (chat, presenter, events) = setup {
            it.client.catalog.setCommands(listOf(Command("clear", "The server's clear")), Location(DIR))
        }

        presenter.onIntent(PaletteIntent.Submit("/clear"))
        settle()

        assertEquals(listOf(CommandRun(MAIN, "clear", "")), chat.client.messages.commandsRun)
        assertEquals(listOf<PaletteEvent>(PaletteEvent.CommandSent(from = "/clear")), events)

        events.clear()
        presenter.onIntent(PaletteIntent.Submit("/new"))
        settle()

        assertEquals(listOf<PaletteEvent>(PaletteEvent.RunBuiltIn(BuiltInCommand.New, from = "/new", draft = "")), events)
        assertEquals(1, chat.client.messages.commandsRun.size)
    }

    @Test
    fun `any other slash word goes back to be sent as a prompt`() = runTest {
        val (chat, presenter, events) = setup()

        presenter.onIntent(PaletteIntent.Submit("/hello there"))
        settle()
        // A server command matches only exactly.
        presenter.onIntent(PaletteIntent.Submit("/Review"))
        settle()
        presenter.onIntent(PaletteIntent.Submit("/cle"))
        settle()

        assertEquals(
            listOf<PaletteEvent>(
                PaletteEvent.SendPrompt("/hello there"),
                PaletteEvent.SendPrompt("/Review"),
                PaletteEvent.SendPrompt("/cle"),
            ),
            events,
        )
        assertTrue(chat.client.messages.commandsRun.isEmpty())
    }

    @Test
    fun `a draft without a slash word goes back at once`() = runTest {
        val (chat, presenter, events) = setup()

        presenter.onIntent(PaletteIntent.Submit("hello /clear"))
        settle()

        assertEquals(listOf<PaletteEvent>(PaletteEvent.SendPrompt("hello /clear")), events)
        assertTrue(chat.catalogCalls().isEmpty())
    }

    @Test
    fun `sending before the lists are known asks for them first, once`() = runTest {
        val (chat, presenter, events) = setup()
        chat.client.control.stall()

        presenter.onIntent(PaletteIntent.Submit("/clear"))
        runCurrent()

        assertTrue(presenter.state.value.running)
        assertTrue(events.isEmpty())
        // One at a time: a second send while the first is worked out is ignored.
        presenter.onIntent(PaletteIntent.Submit("/undo"))

        chat.client.control.resume()
        settle()

        assertEquals(listOf<PaletteEvent>(PaletteEvent.RunBuiltIn(BuiltInCommand.New, from = "/clear", draft = "")), events)
        assertFalse(presenter.state.value.running)

        presenter.onIntent(PaletteIntent.Submit("/undo"))
        settle()
        assertEquals(PaletteEvent.RunBuiltIn(BuiltInCommand.Undo, from = "/undo", draft = ""), events.last())
        // A send needs the commands only, asked once and kept.
        assertEquals(listOf("catalog.commands"), chat.catalogCalls())
    }

    @Test
    fun `when the lists cannot be asked a built-in still runs, and anything else keeps the draft`() = runTest {
        val (chat, presenter, events) = setup { it.connected = false }

        presenter.onIntent(PaletteIntent.Submit("/sessions"))
        settle()

        assertEquals(
            listOf<PaletteEvent>(PaletteEvent.RunBuiltIn(BuiltInCommand.Sessions, from = "/sessions", draft = "")),
            events,
        )
        assertNull(presenter.state.value.error)

        presenter.onIntent(PaletteIntent.Submit("/review now"))
        settle()

        assertEquals(1, events.size)
        assertEquals(PaletteError(PaletteAction.RunCommand, ChatFailure.NotConnected), presenter.state.value.error)
        assertFalse(presenter.state.value.running)
    }

    @Test
    fun `a send is ignored while a command runs`() = runTest {
        val (chat, presenter, events) = setup()
        type(presenter, "/rev")
        chat.client.control.stall()
        presenter.onIntent(PaletteIntent.Pick(PaletteItem.Command("review", "Review changes")))
        runCurrent()

        presenter.onIntent(PaletteIntent.Submit("/clear"))
        chat.client.control.resume()
        settle()

        assertEquals(listOf<PaletteEvent>(PaletteEvent.CommandSent(from = "/rev")), events)
    }


    // Commands and skills load apart

    @Test
    fun `a failed skills list keeps the commands that loaded`() = runTest {
        val (_, presenter) = setup { it.skillsFailure = ApiError.Server(500) }

        type(presenter, "/")

        assertEquals(PaletteError(PaletteAction.Load, ChatFailure.ServerFailed), presenter.state.value.error)
        assertEquals(listOf("/review", "/init", "/preview") + ALL_BUILT_INS, presenter.state.value.names())
    }

    @Test
    fun `a failed commands list keeps the skills that loaded`() = runTest {
        val (_, presenter) = setup { it.commandsFailure = ApiError.Server(500) }

        type(presenter, "/rel")

        assertEquals(PaletteError(PaletteAction.Load, ChatFailure.ServerFailed), presenter.state.value.error)
        assertEquals(listOf("skill:release"), presenter.state.value.names())
    }

    @Test
    fun `after a failed skills list, retry asks for the skills only`() = runTest {
        val (chat, presenter) = setup { it.skillsFailure = ApiError.Server(500) }
        type(presenter, "/")
        chat.skillsFailure = null

        presenter.onIntent(PaletteIntent.Retry)
        settle()

        assertNull(presenter.state.value.error)
        assertEquals(5 + ALL_BUILT_INS.size, presenter.state.value.items.size)
        assertEquals(1, chat.catalogCalls().count { it == "catalog.commands" })
    }

    @Test
    fun `a sent built-in name that a loaded server command overrides runs the server command, even when skills failed`() =
        runTest {
            val (chat, presenter, events) = setup {
                it.client.catalog.setCommands(listOf(Command("new", "The server's new")), Location(DIR))
                it.skillsFailure = ApiError.Server(500)
            }

            presenter.onIntent(PaletteIntent.Submit("/new"))
            settle()

            assertEquals(listOf(CommandRun(MAIN, "new", "")), chat.client.messages.commandsRun)
            assertEquals(listOf<PaletteEvent>(PaletteEvent.CommandSent(from = "/new")), events)
            assertNull(presenter.state.value.error)
        }

    @Test
    fun `a sent built-in name uses the commands the palette loaded although its skills failed`() = runTest {
        val (chat, presenter, events) = setup {
            it.client.catalog.setCommands(listOf(Command("new", "The server's new")), Location(DIR))
            it.skillsFailure = ApiError.Server(500)
        }
        type(presenter, "/ne")
        val asked = chat.catalogCalls().count { it == "catalog.commands" }

        presenter.onIntent(PaletteIntent.Submit("/new"))
        settle()

        assertEquals(listOf(CommandRun(MAIN, "new", "")), chat.client.messages.commandsRun)
        assertTrue(events.none { it is PaletteEvent.RunBuiltIn })
        assertEquals(asked, chat.catalogCalls().count { it == "catalog.commands" })
    }

    // Failures of a sent command stay after the palette closed

    @Test
    fun `a sent command with text that fails keeps its error while the palette is closed`() = runTest {
        val (chat, presenter, events) = setup()
        type(presenter, "/review branch")
        assertFalse(presenter.state.value.open)
        chat.client.control.failNext(ApiError.Server(500))

        presenter.onIntent(PaletteIntent.Submit("/review branch"))
        settle()

        assertTrue(events.isEmpty())
        assertFalse(presenter.state.value.open)
        assertEquals(PaletteError(PaletteAction.RunCommand, ChatFailure.ServerFailed), presenter.state.value.error)
    }

    @Test
    fun `a sent command whose commands list fails keeps that error while the palette is closed`() = runTest {
        val (chat, presenter, events) = setup { it.commandsFailure = ApiError.Network }
        type(presenter, "/review branch")

        presenter.onIntent(PaletteIntent.Submit("/review branch"))
        settle()

        assertTrue(events.isEmpty())
        assertFalse(presenter.state.value.open)
        assertEquals(PaletteError(PaletteAction.RunCommand, ChatFailure.Unreachable), presenter.state.value.error)
        assertTrue(chat.client.messages.commandsRun.isEmpty())
    }

    // A command run cut short, and a send ahead of the stored draft

    @Test
    fun `a command run cut short frees the slot, keeps the draft and shows the error`() = runTest {
        val (chat, presenter, events) = setup { it.runsCancelled = true }

        presenter.onIntent(PaletteIntent.Submit("/review now"))
        settle()

        assertFalse(presenter.state.value.running)
        assertTrue(events.isEmpty())
        assertEquals(PaletteError(PaletteAction.RunCommand, ChatFailure.Unknown), presenter.state.value.error)

        chat.runsCancelled = false
        presenter.onIntent(PaletteIntent.Submit("/review again"))
        settle()

        assertEquals(listOf(CommandRun(MAIN, "review", "again")), chat.client.messages.commandsRun)
        assertEquals(listOf<PaletteEvent>(PaletteEvent.CommandSent(from = "/review again")), events)
    }

    @Test
    fun `a send ahead of the stored draft runs the sent text and names only that draft`() = runTest {
        val (chat, presenter, events) = setup()
        // The palette last saw an older draft; the composer already holds the newer one it sends.
        type(presenter, "/rev")

        presenter.onIntent(PaletteIntent.Submit("/review the newest text"))
        settle()

        assertEquals(listOf(CommandRun(MAIN, "review", "the newest text")), chat.client.messages.commandsRun)
        assertEquals(listOf<PaletteEvent>(PaletteEvent.CommandSent(from = "/review the newest text")), events)
        assertTrue(events.none { it.toString().contains("/rev") })
    }

    // Notices

    @Test
    fun `a notice shows until the draft changes or it is dismissed`() = runTest {
        val (_, presenter) = setup()
        type(presenter, "/undo")

        presenter.onIntent(PaletteIntent.ShowNotice(BuiltInNotice.NothingToUndo))
        runCurrent()
        assertEquals(BuiltInNotice.NothingToUndo, presenter.state.value.notice)

        // The same draft handed over again is no change.
        type(presenter, "/undo")
        assertEquals(BuiltInNotice.NothingToUndo, presenter.state.value.notice)

        type(presenter, "/und")
        assertNull(presenter.state.value.notice)

        presenter.onIntent(PaletteIntent.ShowNotice(BuiltInNotice.Unavailable))
        presenter.onIntent(PaletteIntent.DismissNotice)
        runCurrent()
        assertNull(presenter.state.value.notice)
    }

    @Test
    fun `sent drafts stay out of what prints`() {
        val printed = listOf(
            PaletteIntent.Submit("/review secret plan"),
            PaletteEvent.RunBuiltIn(BuiltInCommand.New, from = "/new secret plan", draft = "secret plan"),
            PaletteEvent.SendPrompt("/hello secret plan"),
        ).joinToString()

        assertFalse("secret" in printed)
    }

    // Privacy

    @Test
    fun `the draft and paths stay out of what prints`() = runTest {
        val (_, presenter) = setup()
        type(presenter, "@READ")

        val printed = presenter.state.value.toString()

        assertFalse("READ" in printed)
        assertFalse("README" in printed)
    }

    // Skills by id (P9.33)

    /** Skills as the real server lists them: built-in ones have an id other than their name. */
    private fun FakeChatSession.seedServerSkills() {
        client.catalog.setSkills(
            listOf(
                skill("OpenCode", "Use opencode", id = "opencode"),
                skill("Browser", "Drive a browser", id = "skl-web"),
                skill("granny-workflow", "Granny's steps"),
            ),
            Location(DIR),
        )
    }

    @Test
    fun `a skill item carries its id and its name, and a pick attaches the id`() = runTest {
        val (_, presenter, events) = setup { it.seedServerSkills() }
        type(presenter, "/open")

        val item = PaletteItem.Skill(id = "opencode", name = "OpenCode", description = "Use opencode")
        assertEquals(listOf<PaletteItem>(item), presenter.state.value.items)

        presenter.onIntent(PaletteIntent.Pick(item))
        settle()

        assertEquals(listOf<PaletteEvent>(PaletteEvent.Attached("", PromptAttachment.Skill("opencode"))), events)
    }

    @Test
    fun `a skill matches its id or its name, in any case`() = runTest {
        val (_, presenter) = setup { it.seedServerSkills() }

        type(presenter, "/SKL-")
        assertEquals(listOf("skill:skl-web"), presenter.state.value.names())

        type(presenter, "/brow")
        assertEquals(listOf("skill:skl-web"), presenter.state.value.names())

        type(presenter, "/GRANNY")
        assertEquals(listOf("skill:granny-workflow"), presenter.state.value.names())
    }

    @Test
    fun `sending a skill's exact id sends the rest of the draft with that skill attached`() = runTest {
        val (chat, presenter, events) = setup { it.seedServerSkills() }

        presenter.onIntent(PaletteIntent.Submit("/granny-workflow do X\n and Y"))
        settle()
        presenter.onIntent(PaletteIntent.Submit("  /opencode  "))
        settle()

        assertEquals(
            listOf<PaletteEvent>(
                PaletteEvent.SendSkill(
                    PromptAttachment.Skill("granny-workflow"),
                    from = "/granny-workflow do X\n and Y",
                    text = "do X\n and Y",
                ),
                PaletteEvent.SendSkill(PromptAttachment.Skill("opencode"), from = "  /opencode  ", text = ""),
            ),
            events,
        )
        assertTrue(chat.client.messages.commandsRun.isEmpty())
        assertFalse(presenter.state.value.running)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a skill's name, or its id in another case, is no skill to send`() = runTest {
        val (_, presenter, events) = setup { it.seedServerSkills() }

        presenter.onIntent(PaletteIntent.Submit("/OpenCode hi"))
        settle()
        presenter.onIntent(PaletteIntent.Submit("/Granny-workflow"))
        settle()

        assertEquals(
            listOf<PaletteEvent>(PaletteEvent.SendPrompt("/OpenCode hi"), PaletteEvent.SendPrompt("/Granny-workflow")),
            events,
        )
    }

    @Test
    fun `a server command, then a built-in, wins over a skill of the same id`() = runTest {
        val (chat, presenter, events) = setup {
            it.client.catalog.setCommands(listOf(Command("granny-workflow")), Location(DIR))
            it.client.catalog.setSkills(listOf(skill("granny-workflow"), skill("new"), skill("clear")), Location(DIR))
        }

        presenter.onIntent(PaletteIntent.Submit("/granny-workflow go"))
        settle()
        presenter.onIntent(PaletteIntent.Submit("/new"))
        settle()
        presenter.onIntent(PaletteIntent.Submit("/clear"))
        settle()

        assertEquals(listOf(CommandRun(MAIN, "granny-workflow", "go")), chat.client.messages.commandsRun)
        assertEquals(
            listOf<PaletteEvent>(
                PaletteEvent.CommandSent(from = "/granny-workflow go"),
                PaletteEvent.RunBuiltIn(BuiltInCommand.New, from = "/new", draft = ""),
                PaletteEvent.RunBuiltIn(BuiltInCommand.New, from = "/clear", draft = ""),
            ),
            events,
        )
    }

    @Test
    fun `a skill sent while the skills load waits for them, sharing the palette's request`() = runTest {
        val (chat, presenter, events) = setup { it.seedServerSkills() }
        chat.client.control.stall()
        type(presenter, "/granny-workflow")
        assertTrue(presenter.state.value.loading)

        presenter.onIntent(PaletteIntent.Submit("/granny-workflow"))
        runCurrent()
        assertTrue(presenter.state.value.running)
        assertTrue(events.isEmpty())

        chat.client.control.resume()
        settle()

        assertEquals(
            listOf<PaletteEvent>(PaletteEvent.SendSkill(PromptAttachment.Skill("granny-workflow"), from = "/granny-workflow", text = "")),
            events,
        )
        assertEquals(1, chat.catalogCalls().count { it == "catalog.skills" })
        assertEquals(1, chat.catalogCalls().count { it == "catalog.commands" })
        assertFalse(presenter.state.value.running)
    }

    @Test
    fun `a slash word sent before any list is known asks for the commands, then the skills, once each`() = runTest {
        val (chat, presenter, events) = setup { it.seedServerSkills() }

        presenter.onIntent(PaletteIntent.Submit("/opencode fix it"))
        settle()
        presenter.onIntent(PaletteIntent.Submit("/hello"))
        settle()

        assertEquals(
            listOf<PaletteEvent>(
                PaletteEvent.SendSkill(PromptAttachment.Skill("opencode"), from = "/opencode fix it", text = "fix it"),
                PaletteEvent.SendPrompt("/hello"),
            ),
            events,
        )
        assertEquals(listOf("catalog.commands", "catalog.skills"), chat.catalogCalls())
    }

    @Test
    fun `when the skills cannot be had a slash word falls through to a built-in or a plain prompt`() = runTest {
        val (_, presenter, events) = setup {
            it.seedServerSkills()
            it.skillsFailure = ApiError.Server(500)
        }

        presenter.onIntent(PaletteIntent.Submit("/granny-workflow do X"))
        settle()
        presenter.onIntent(PaletteIntent.Submit("/compact"))
        settle()

        assertEquals(
            listOf<PaletteEvent>(
                PaletteEvent.SendPrompt("/granny-workflow do X"),
                PaletteEvent.RunBuiltIn(BuiltInCommand.Compact, from = "/compact", draft = ""),
            ),
            events,
        )
        assertFalse(presenter.state.value.running)
    }

    @Test
    fun `when only the commands cannot be had a known skill is still sent`() = runTest {
        val (_, presenter, events) = setup {
            it.seedServerSkills()
            it.commandsFailure = ApiError.Server(500)
        }

        presenter.onIntent(PaletteIntent.Submit("/granny-workflow do X"))
        settle()

        assertEquals(
            listOf<PaletteEvent>(
                PaletteEvent.SendSkill(PromptAttachment.Skill("granny-workflow"), from = "/granny-workflow do X", text = "do X"),
            ),
            events,
        )
        assertNull(presenter.state.value.error)
        assertFalse(presenter.state.value.running)
    }

    @Test
    fun `a sent skill's draft and text stay out of what prints`() {
        val event = PaletteEvent.SendSkill(
            PromptAttachment.Skill("granny-workflow"),
            from = "/granny-workflow secret plan",
            text = "secret plan",
        )

        assertFalse(event.toString().contains("secret"))
    }
}
