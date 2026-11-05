package io.github.thewisekd.phopencode.feature.chat.logic.palette

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.FileSearchType
import io.github.thewisekd.phopencode.core.api.FsApi
import io.github.thewisekd.phopencode.core.api.Location
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

    /** How long a file search takes, by query; none by default. */
    val findDelays = mutableMapOf<String, Long>()

    val finds = mutableListOf<Find>()

    private val recording = object : OpencodeClient by client {
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

private fun skill(name: String, description: String? = null) =
    Skill(id = "skl_$name", name = name, description = description, path = "/skills/$name.md", content = "body of $name")

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
            is PaletteItem.Skill -> "skill:${it.name}"
            is PaletteItem.Agent -> "@${it.id}"
            is PaletteItem.File -> "file:${it.path}"
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
    fun `a slash as the first word lists commands then skills for the session's folder`() = runTest {
        val (chat, presenter) = setup()

        type(presenter, "/")

        val state = presenter.state.value
        assertEquals(PaletteTrigger.Slash, state.trigger)
        assertEquals("", state.query)
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(listOf("/review", "/init", "/preview", "skill:release", "skill:Refactor"), state.names())
        assertEquals(PaletteItem.Command("review", "Review changes"), state.items.first())
        assertEquals(PaletteItem.Skill("release", "Cut a release"), state.items[3])
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
        // Commands then skills; within each kind, prefix matches lead.
        assertEquals(listOf("/review", "/preview", "skill:release", "skill:Refactor"), presenter.state.value.names())
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

        assertEquals(listOf("/deploy"), presenter.state.value.names())
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
        assertEquals(5, presenter.state.value.items.size)
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

        presenter.onIntent(PaletteIntent.Pick(PaletteItem.Skill("release", "Cut a release")))
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
        assertEquals(listOf("/format"), presenter.state.value.names())

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
        assertEquals(listOf<PaletteEvent>(PaletteEvent.CommandSent("/rev and more")), events)
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
        assertEquals(listOf<PaletteEvent>(PaletteEvent.CommandSent("")), events)
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

    // Privacy

    @Test
    fun `the draft and paths stay out of what prints`() = runTest {
        val (_, presenter) = setup()
        type(presenter, "@READ")

        val printed = presenter.state.value.toString()

        assertFalse("READ" in printed)
        assertFalse("README" in printed)
    }
}
