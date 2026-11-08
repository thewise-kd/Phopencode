package io.github.thewisekd.phopencode.feature.catalog.ui.registry

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.thewisekd.phopencode.core.api.Provider
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.component.StatusChip
import io.github.thewisekd.phopencode.core.ui.component.StatusTone
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogList
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryTab
import io.github.thewisekd.phopencode.feature.catalog.ui.R

/** Marks the list of the shown tab, for tests. */
internal const val REGISTRY_LIST_TAG = "catalog-registry:list"

/** A command is typed with a slash in front of it, so it is shown that way. A mark, not a word. */
internal const val COMMAND_PREFIX = "/"

/**
 * The server's commands, agents and models, one tab each. Draws [state] and sends [onIntent]; it
 * decides nothing itself.
 *
 * Each tab shows its own list's progress, failure and emptiness, since the lists are read on their
 * own: one failing leaves the others. A row is a rounded card, as in the session and server lists,
 * and a tap opens its details. The top bar's button reads the shown tab's list again. Progress is a
 * still message, never a spinner: an endless animation keeps Robolectric's `idle()` in the app's
 * tests from returning.
 *
 * Descriptions are the user's configuration: they are drawn, never logged.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun RegistryScreen(
    route: CatalogRoute.Registry,
    state: RegistryState,
    onIntent: (RegistryIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shownLoading = when (state.tab) {
        RegistryTab.Commands -> state.commands.loading
        RegistryTab.Agents -> state.agents.loading
        RegistryTab.Models -> state.models.loading
    }
    PhopencodeScaffold(
        title = stringResource(R.string.catalog_registry_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(RegistryIntent.Close) },
        actions = {
            IconButton(onClick = { onIntent(RegistryIntent.Reload(state.tab)) }, enabled = !shownLoading) {
                Icon(
                    imageVector = PhopencodeIcons.Refresh,
                    contentDescription = stringResource(R.string.catalog_registry_reload),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                RegistryTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == state.tab,
                        onClick = { onIntent(RegistryIntent.SelectTab(tab)) },
                        text = { Text(stringResource(tab.labelRes), maxLines = 1) },
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (state.tab) {
                    RegistryTab.Commands -> TabList(state.commands, RegistryTab.Commands, onIntent) { items ->
                        items(items, key = { "command:${it.name}" }) { CommandRow(it, onIntent) }
                    }
                    RegistryTab.Agents -> TabList(state.agents, RegistryTab.Agents, onIntent) { items ->
                        items(items, key = { "agent:${it.id}" }) { AgentRow(it, onIntent) }
                    }
                    RegistryTab.Models -> TabList(state.models, RegistryTab.Models, onIntent) { items ->
                        items(items, key = { "model:${it.providerId}/${it.id}" }) {
                            ModelRow(it, state.providers, state.defaultModel, onIntent)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One tab's list: its failure first, then its rows, with a still progress line above them while it
 * is read again; the progress alone while nothing was read yet, or the empty state when the server
 * has none.
 */
@Composable
private fun <T> TabList(
    list: CatalogList<T>,
    tab: RegistryTab,
    onIntent: (RegistryIntent) -> Unit,
    rows: LazyListScope.(List<T>) -> Unit,
) {
    val spacing = PhopencodeTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(REGISTRY_LIST_TAG),
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        list.error?.let { failure ->
            item(key = "error") { FailureBanner(failure, onRetry = { onIntent(RegistryIntent.Reload(tab)) }) }
        }
        when {
            list.items.isNotEmpty() -> {
                if (list.loading) {
                    item(key = "reloading") {
                        StillProgress(stringResource(R.string.catalog_registry_reloading), Modifier.fillMaxWidth())
                    }
                }
                rows(list.items)
            }
            list.loading -> item(key = "loading") {
                StillProgress(stringResource(tab.loadingRes), Modifier.fillParentMaxSize())
            }
            list.isEmpty -> item(key = "empty") {
                EmptyState(
                    modifier = Modifier.fillParentMaxSize(),
                    title = stringResource(tab.emptyTitleRes),
                    message = stringResource(tab.emptyMessageRes),
                    icon = tab.icon,
                )
            }
        }
    }
}

@Composable
private fun CommandRow(command: Command, onIntent: (RegistryIntent) -> Unit) {
    RowCard(
        onClick = { onIntent(RegistryIntent.OpenCommand(command.name)) },
        onClickLabel = stringResource(R.string.catalog_registry_open),
    ) {
        RowTitle(COMMAND_PREFIX + command.name)
        RowDetail(command.description ?: stringResource(R.string.catalog_registry_no_description))
    }
}

/** An agent's name and description, then its mode, model and whether the server hides it. */
@Composable
private fun AgentRow(agent: Agent, onIntent: (RegistryIntent) -> Unit) {
    RowCard(
        onClick = { onIntent(RegistryIntent.OpenAgent(agent.id)) },
        onClickLabel = stringResource(R.string.catalog_registry_open),
    ) {
        RowTitle(agent.name)
        agent.description?.let { RowDetail(it) }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.xs),
        ) {
            SmallChip(modeText(agent.mode))
            agent.model?.let { SmallChip(it.label()) }
            if (agent.hidden) SmallChip(stringResource(R.string.catalog_registry_hidden))
        }
    }
}

/** A model's name, its provider, its context limit when known, and whether it is the server's default. */
@Composable
private fun ModelRow(
    model: Model,
    providers: List<Provider>,
    defaultModel: ModelRef?,
    onIntent: (RegistryIntent) -> Unit,
) {
    val isDefault = defaultModel != null && defaultModel.providerId == model.providerId && defaultModel.modelId == model.id
    RowCard(
        onClick = { onIntent(RegistryIntent.OpenModel(model.providerId, model.id)) },
        onClickLabel = stringResource(R.string.catalog_registry_open),
    ) {
        RowTitle(model.name)
        RowDetail(providers.firstOrNull { it.id == model.providerId }?.name ?: model.providerId, maxLines = 1)
        model.limit.context?.let {
            Text(
                text = stringResource(R.string.catalog_registry_context_line, tokensText(it)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        if (isDefault) StatusChip(status = StatusTone.Success, label = stringResource(R.string.catalog_registry_default))
    }
}

@get:StringRes
private val RegistryTab.labelRes: Int
    get() = when (this) {
        RegistryTab.Commands -> R.string.catalog_registry_tab_commands
        RegistryTab.Agents -> R.string.catalog_registry_tab_agents
        RegistryTab.Models -> R.string.catalog_registry_tab_models
    }

@get:StringRes
private val RegistryTab.loadingRes: Int
    get() = when (this) {
        RegistryTab.Commands -> R.string.catalog_registry_loading_commands
        RegistryTab.Agents -> R.string.catalog_registry_loading_agents
        RegistryTab.Models -> R.string.catalog_registry_loading_models
    }

@get:StringRes
private val RegistryTab.emptyTitleRes: Int
    get() = when (this) {
        RegistryTab.Commands -> R.string.catalog_registry_empty_commands_title
        RegistryTab.Agents -> R.string.catalog_registry_empty_agents_title
        RegistryTab.Models -> R.string.catalog_registry_empty_models_title
    }

@get:StringRes
private val RegistryTab.emptyMessageRes: Int
    get() = when (this) {
        RegistryTab.Commands -> R.string.catalog_registry_empty_commands_message
        RegistryTab.Agents -> R.string.catalog_registry_empty_agents_message
        RegistryTab.Models -> R.string.catalog_registry_empty_models_message
    }

private val RegistryTab.icon: ImageVector
    get() = when (this) {
        RegistryTab.Commands -> PhopencodeIcons.Terminal
        RegistryTab.Agents -> PhopencodeIcons.Agent
        RegistryTab.Models -> PhopencodeIcons.Sparkle
    }
