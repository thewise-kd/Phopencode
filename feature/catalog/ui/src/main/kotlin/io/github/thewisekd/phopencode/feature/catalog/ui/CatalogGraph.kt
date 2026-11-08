package io.github.thewisekd.phopencode.feature.catalog.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.Presenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.catalog.ui.registry.AgentDetailScreen
import io.github.thewisekd.phopencode.feature.catalog.ui.registry.CommandDetailScreen
import io.github.thewisekd.phopencode.feature.catalog.ui.registry.ModelDetailScreen
import io.github.thewisekd.phopencode.feature.catalog.ui.registry.RegistryScreen
import io.github.thewisekd.phopencode.feature.catalog.ui.skills.SkillDetailScreen
import io.github.thewisekd.phopencode.feature.catalog.ui.skills.SkillsScreen
import io.github.thewisekd.phopencode.feature.catalog.ui.status.StatusScreen
import kotlinx.coroutines.CoroutineScope
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * The catalog screens, one destination per [CatalogRoute]. The app's NavHost calls this; a new screen
 * of the feature is added here only.
 *
 * Each destination gets its presenter from `catalogModule`, hands the screen its state and intents,
 * and turns its one-time events into navigation. The screens never see [navController].
 *
 * The flow:
 * - The skill list opens a skill, whose details start a new session with it (`SessionsRoute.Create`
 *   with the skill's ID).
 * - The registry opens a command, an agent or a model; an agent opens its model.
 * - The skill list and the registry read through their caches again whenever they are shown again,
 *   which costs nothing unless the connection changed meanwhile; the status reads the server again,
 *   since an MCP server's or a plugin's state changes on its own. The detail screens do not.
 *
 * Route arguments are never logged.
 */
fun NavGraphBuilder.catalogGraph(navController: NavController) {
    composable<CatalogRoute.Skills> { entry ->
        val route = entry.toRoute<CatalogRoute.Skills>()
        val skills = rememberPresenter<SkillsPresenter>(route)
        OnShown { again -> if (again) skills.onIntent(SkillsIntent.Refresh) }
        CollectEvents(skills) { event ->
            when (event) {
                is SkillsEvent.OpenSkill -> navController.navigate(CatalogRoute.SkillDetail(event.skillId))
                SkillsEvent.Close -> navController.popBackStack()
            }
        }
        val state by skills.state.collectAsStateWithLifecycle()
        SkillsScreen(route, state, skills::onIntent)
    }
    composable<CatalogRoute.SkillDetail> { entry ->
        val route = entry.toRoute<CatalogRoute.SkillDetail>()
        val skill = rememberPresenter<SkillDetailPresenter>(route)
        CollectEvents(skill) { event ->
            when (event) {
                is SkillDetailEvent.StartSession -> navController.navigate(SessionsRoute.Create(skillId = event.skillId))
                SkillDetailEvent.Close -> navController.popBackStack()
            }
        }
        val state by skill.state.collectAsStateWithLifecycle()
        SkillDetailScreen(route, state, skill::onIntent)
    }
    composable<CatalogRoute.Registry> { entry ->
        val route = entry.toRoute<CatalogRoute.Registry>()
        val registry = rememberPresenter<RegistryPresenter>(route)
        OnShown { again -> if (again) registry.onIntent(RegistryIntent.Refresh) }
        CollectEvents(registry) { event ->
            when (event) {
                is RegistryEvent.OpenCommand -> navController.navigate(CatalogRoute.CommandDetail(event.name))
                is RegistryEvent.OpenAgent -> navController.navigate(CatalogRoute.AgentDetail(event.agentId))
                is RegistryEvent.OpenModel -> navController.navigate(CatalogRoute.ModelDetail(event.providerId, event.modelId))
                RegistryEvent.Close -> navController.popBackStack()
            }
        }
        val state by registry.state.collectAsStateWithLifecycle()
        RegistryScreen(route, state, registry::onIntent)
    }
    composable<CatalogRoute.CommandDetail> { entry ->
        val route = entry.toRoute<CatalogRoute.CommandDetail>()
        val command = rememberPresenter<CommandDetailPresenter>(route)
        CollectEvents(command) { event ->
            when (event) {
                CommandDetailEvent.Close -> navController.popBackStack()
            }
        }
        val state by command.state.collectAsStateWithLifecycle()
        CommandDetailScreen(route, state, command::onIntent)
    }
    composable<CatalogRoute.AgentDetail> { entry ->
        val route = entry.toRoute<CatalogRoute.AgentDetail>()
        val agent = rememberPresenter<AgentDetailPresenter>(route)
        CollectEvents(agent) { event ->
            when (event) {
                is AgentDetailEvent.OpenModel -> navController.navigate(CatalogRoute.ModelDetail(event.providerId, event.modelId))
                AgentDetailEvent.Close -> navController.popBackStack()
            }
        }
        val state by agent.state.collectAsStateWithLifecycle()
        AgentDetailScreen(route, state, agent::onIntent)
    }
    composable<CatalogRoute.ModelDetail> { entry ->
        val route = entry.toRoute<CatalogRoute.ModelDetail>()
        val model = rememberPresenter<ModelDetailPresenter>(route)
        CollectEvents(model) { event ->
            when (event) {
                ModelDetailEvent.Close -> navController.popBackStack()
            }
        }
        val state by model.state.collectAsStateWithLifecycle()
        ModelDetailScreen(route, state, model::onIntent)
    }
    composable<CatalogRoute.Status> { entry ->
        val route = entry.toRoute<CatalogRoute.Status>()
        val status = rememberPresenter<StatusPresenter>(route)
        OnShown { again -> if (again) status.onIntent(StatusIntent.Refresh) }
        CollectEvents(status) { event ->
            when (event) {
                StatusEvent.Close -> navController.popBackStack()
            }
        }
        val state by status.state.collectAsStateWithLifecycle()
        StatusScreen(route, state, status::onIntent)
    }
}

/** Whether the destination was shown before; lives as long as its back stack entry. */
private class ShownBefore : ViewModel() {
    var value: Boolean = false
}

/**
 * Runs [action] each time the destination is resumed: `again` is false the first time, true when it
 * is shown again, as after the screen above it closed or the app came back to the front. A covered
 * destination leaves the composition, so the flag lives in the entry's view model, which also keeps
 * it over a configuration change: a rotation counts as shown again.
 */
@Composable
private fun OnShown(action: (again: Boolean) -> Unit) {
    val shown = viewModel<ShownBefore>(key = ShownBefore::class.java.name) { ShownBefore() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val handle by rememberUpdatedState(action)
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            handle(shown.value)
            shown.value = true
        }
    }
}

/** Keeps a presenter, and the scope it runs in, for as long as its back stack entry lives. */
private class PresenterHolder<P : Any>(create: (CoroutineScope) -> P) : ViewModel() {
    val presenter: P = create(viewModelScope)
}

/**
 * The presenter of the destination showing [route], from Koin with the parameters `catalogModule`
 * expects: the route and the scope of the destination's view model. It survives configuration
 * changes and stops when the destination leaves the back stack, which cancels whatever it runs.
 *
 * Koin is the one the app started, looked up when the presenter is made. Compose's `getKoin()` is
 * not used: it can keep a Koin that was stopped since, as Robolectric tests stop and start it.
 */
@Composable
private inline fun <reified P : Any> rememberPresenter(route: CatalogRoute): P {
    val holder = viewModel<PresenterHolder<P>>(key = P::class.java.name) {
        PresenterHolder { scope -> KoinPlatform.getKoin().get<P> { parametersOf(route, scope) } }
    }
    return holder.presenter
}

/** Delivers [presenter]'s one-time events while the destination is at least started. */
@Composable
private fun <E> CollectEvents(presenter: Presenter<*, *, E>, onEvent: suspend (E) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val handle by rememberUpdatedState(onEvent)
    LaunchedEffect(presenter, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            presenter.events.collect { handle(it) }
        }
    }
}
