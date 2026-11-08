package io.github.thewisekd.phopencode.feature.catalog.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.Route
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.Presenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusState
import java.util.Collections
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

private const val SKILL = "gözden geçir/çalışma"
private const val COMMAND = "review"
private const val AGENT = "build"
private const val PROVIDER = "provider"
private const val MODEL = "model/large"

/** A presenter that records the intents it gets and sends the events a test puts in [sent]. */
private class Recording<S, I, E>(initial: S) : Presenter<S, I, E> {
    val sent = Channel<E>(Channel.UNLIMITED)
    val intents: MutableList<I> = Collections.synchronizedList(mutableListOf())

    override val state: StateFlow<S> = MutableStateFlow(initial)
    override val events: Flow<E> = sent.receiveAsFlow()

    override fun onIntent(intent: I) {
        intents += intent
    }
}

/**
 * The graph opens every route's screen, turns each presenter's events into navigation between the
 * catalog routes and to a new session, and refreshes the lists when shown again. The presenters are
 * fakes; one instance per type serves every destination.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class CatalogGraphTest {
    @get:Rule
    val compose = createComposeRule()

    private val skills = Recording<SkillsState, SkillsIntent, SkillsEvent>(SkillsState())
    private val skill = Recording<SkillDetailState, SkillDetailIntent, SkillDetailEvent>(SkillDetailState(skillId = SKILL))
    private val registry = Recording<RegistryState, RegistryIntent, RegistryEvent>(RegistryState())
    private val command = Recording<CommandDetailState, CommandDetailIntent, CommandDetailEvent>(CommandDetailState(name = COMMAND))
    private val agent = Recording<AgentDetailState, AgentDetailIntent, AgentDetailEvent>(AgentDetailState(agentId = AGENT))
    private val model =
        Recording<ModelDetailState, ModelDetailIntent, ModelDetailEvent>(ModelDetailState(providerId = PROVIDER, modelId = MODEL))
    private val status = Recording<StatusState, StatusIntent, StatusEvent>(StatusState())

    private lateinit var navController: NavHostController

    @Before
    fun startDi() {
        startKoin {
            modules(
                module {
                    factory<SkillsPresenter> { object : SkillsPresenter, Presenter<SkillsState, SkillsIntent, SkillsEvent> by skills {} }
                    factory<SkillDetailPresenter> {
                        object : SkillDetailPresenter, Presenter<SkillDetailState, SkillDetailIntent, SkillDetailEvent> by skill {}
                    }
                    factory<RegistryPresenter> {
                        object : RegistryPresenter, Presenter<RegistryState, RegistryIntent, RegistryEvent> by registry {}
                    }
                    factory<CommandDetailPresenter> {
                        object :
                            CommandDetailPresenter,
                            Presenter<CommandDetailState, CommandDetailIntent, CommandDetailEvent> by command {}
                    }
                    factory<AgentDetailPresenter> {
                        object : AgentDetailPresenter, Presenter<AgentDetailState, AgentDetailIntent, AgentDetailEvent> by agent {}
                    }
                    factory<ModelDetailPresenter> {
                        object : ModelDetailPresenter, Presenter<ModelDetailState, ModelDetailIntent, ModelDetailEvent> by model {}
                    }
                    factory<StatusPresenter> { object : StatusPresenter, Presenter<StatusState, StatusIntent, StatusEvent> by status {} }
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
                    composable<ConnectRoute.ServerList> { Stand("start") }
                    composable<SessionsRoute.Create> { Stand("create") }
                    catalogGraph(navController)
                }
            }
        }
    }

    @Composable
    private fun Stand(tag: String) {
        Box(Modifier.fillMaxSize().testTag(tag))
    }

    private fun open(vararg routes: Route) {
        routes.forEach { route -> compose.runOnIdle { navController.navigate(route) } }
        compose.waitForIdle()
    }

    private fun <E> send(presenter: Recording<*, *, E>, event: E) {
        presenter.sent.trySend(event)
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnIdle { navController.popBackStack() }
        compose.waitForIdle()
    }

    private fun routeOf(entry: NavBackStackEntry): Route? {
        val destination = entry.destination
        return when {
            destination.hasRoute<ConnectRoute.ServerList>() -> ConnectRoute.ServerList
            destination.hasRoute<SessionsRoute.Create>() -> entry.toRoute<SessionsRoute.Create>()
            destination.hasRoute<CatalogRoute.Skills>() -> CatalogRoute.Skills
            destination.hasRoute<CatalogRoute.SkillDetail>() -> entry.toRoute<CatalogRoute.SkillDetail>()
            destination.hasRoute<CatalogRoute.Registry>() -> CatalogRoute.Registry
            destination.hasRoute<CatalogRoute.CommandDetail>() -> entry.toRoute<CatalogRoute.CommandDetail>()
            destination.hasRoute<CatalogRoute.AgentDetail>() -> entry.toRoute<CatalogRoute.AgentDetail>()
            destination.hasRoute<CatalogRoute.ModelDetail>() -> entry.toRoute<CatalogRoute.ModelDetail>()
            destination.hasRoute<CatalogRoute.Status>() -> CatalogRoute.Status
            else -> null // The graph itself.
        }
    }

    private fun backStack(): List<Route> = compose.runOnIdle {
        navController.currentBackStack.value.mapNotNull(::routeOf)
    }

    private fun <I> intents(presenter: Recording<*, I, *>): List<I> = compose.runOnIdle { presenter.intents.toList() }

    @Test
    fun `every route shows its screen with its tag`() {
        setNavHost()
        val routes = listOf(
            CatalogRoute.Skills,
            CatalogRoute.SkillDetail(SKILL),
            CatalogRoute.Registry,
            CatalogRoute.CommandDetail(COMMAND),
            CatalogRoute.AgentDetail(AGENT),
            CatalogRoute.ModelDetail(PROVIDER, MODEL),
            CatalogRoute.Status,
        )

        routes.forEach { route ->
            open(route)
            compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
        }
    }

    @Test
    fun `a skill opens from the list and starts a new session with it`() {
        setNavHost()
        open(CatalogRoute.Skills)

        send(skills, SkillsEvent.OpenSkill(SKILL))
        assertEquals(listOf(ConnectRoute.ServerList, CatalogRoute.Skills, CatalogRoute.SkillDetail(SKILL)), backStack())
        send(skill, SkillDetailEvent.StartSession(SKILL))

        assertEquals(
            listOf(
                ConnectRoute.ServerList,
                CatalogRoute.Skills,
                CatalogRoute.SkillDetail(SKILL),
                SessionsRoute.Create(skillId = SKILL),
            ),
            backStack(),
        )
    }

    @Test
    fun `the skill list's first showing asks for nothing, and it refreshes when shown again`() {
        setNavHost()
        open(CatalogRoute.Skills)
        assertTrue(intents(skills).isEmpty())

        send(skills, SkillsEvent.OpenSkill(SKILL))
        back()

        assertEquals(listOf<SkillsIntent>(SkillsIntent.Refresh), intents(skills))
        assertTrue(intents(skill).isEmpty(), "the details do not refresh")
    }

    @Test
    fun `closing a skill and the list goes back`() {
        setNavHost()
        open(CatalogRoute.Skills, CatalogRoute.SkillDetail(SKILL))

        send(skill, SkillDetailEvent.Close)
        assertEquals(listOf(ConnectRoute.ServerList, CatalogRoute.Skills), backStack())
        send(skills, SkillsEvent.Close)

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
    }

    @Test
    fun `the registry opens a command, an agent and a model`() {
        setNavHost()
        open(CatalogRoute.Registry)
        val opened = listOf(
            RegistryEvent.OpenCommand(COMMAND) to CatalogRoute.CommandDetail(COMMAND),
            RegistryEvent.OpenAgent(AGENT) to CatalogRoute.AgentDetail(AGENT),
            RegistryEvent.OpenModel(PROVIDER, MODEL) to CatalogRoute.ModelDetail(PROVIDER, MODEL),
        )

        opened.forEach { (event, route) ->
            send(registry, event)
            assertEquals(listOf(ConnectRoute.ServerList, CatalogRoute.Registry, route), backStack())
            back()
        }
        assertEquals(List<RegistryIntent>(opened.size) { RegistryIntent.Refresh }, intents(registry))
    }

    @Test
    fun `an agent opens its model`() {
        setNavHost()
        open(CatalogRoute.Registry, CatalogRoute.AgentDetail(AGENT))

        send(agent, AgentDetailEvent.OpenModel(PROVIDER, MODEL))

        assertEquals(
            listOf(
                ConnectRoute.ServerList,
                CatalogRoute.Registry,
                CatalogRoute.AgentDetail(AGENT),
                CatalogRoute.ModelDetail(PROVIDER, MODEL),
            ),
            backStack(),
        )
    }

    @Test
    fun `closing the details and the registry goes back`() {
        setNavHost()
        open(
            CatalogRoute.Registry,
            CatalogRoute.CommandDetail(COMMAND),
            CatalogRoute.AgentDetail(AGENT),
            CatalogRoute.ModelDetail(PROVIDER, MODEL),
        )

        send(model, ModelDetailEvent.Close)
        send(agent, AgentDetailEvent.Close)
        send(command, CommandDetailEvent.Close)
        assertEquals(listOf(ConnectRoute.ServerList, CatalogRoute.Registry), backStack())
        send(registry, RegistryEvent.Close)

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
    }

    @Test
    fun `the status refreshes when shown again and goes back when closed`() {
        setNavHost()
        open(CatalogRoute.Status, CatalogRoute.Skills)
        assertTrue(intents(status).isEmpty())

        back()
        assertEquals(listOf<StatusIntent>(StatusIntent.Refresh), intents(status))
        send(status, StatusEvent.Close)

        assertEquals(listOf(ConnectRoute.ServerList), backStack())
    }
}
