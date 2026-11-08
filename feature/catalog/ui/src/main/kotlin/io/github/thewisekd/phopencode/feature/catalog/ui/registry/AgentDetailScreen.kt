package io.github.thewisekd.phopencode.feature.catalog.ui.registry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.PermissionEffect
import io.github.thewisekd.phopencode.core.model.PermissionRule
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.component.StatusChip
import io.github.thewisekd.phopencode.core.ui.component.StatusTone
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailState
import io.github.thewisekd.phopencode.feature.catalog.ui.R

/**
 * One agent: its description, its mode, its model, which opens the model's details, and its
 * permission rules, each with its effect as a status chip whose label, icon and color agree. Draws
 * [state] and sends [onIntent]; it decides nothing itself. The title is the agent's ID from the route
 * until the agent is read, then its display name.
 *
 * A permission's resource can be a path: it is drawn, never logged.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun AgentDetailScreen(
    route: CatalogRoute.AgentDetail,
    state: AgentDetailState,
    onIntent: (AgentDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val agent = state.agent
    PhopencodeScaffold(
        title = agent?.name ?: state.agentId,
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(AgentDetailIntent.Close) },
    ) { padding ->
        DetailBody(
            loading = state.loading,
            failure = state.error,
            loadingMessage = stringResource(R.string.catalog_registry_loading_agent),
            hasContent = agent != null,
            onRetry = { onIntent(AgentDetailIntent.Retry) },
            modifier = Modifier.padding(padding),
        ) {
            if (agent != null) AgentDetails(agent, onIntent)
        }
    }
}

@Composable
private fun AgentDetails(agent: Agent, onIntent: (AgentDetailIntent) -> Unit) {
    Section(stringResource(R.string.catalog_registry_section_description)) {
        val description = agent.description
        if (description != null) {
            BodyText(description)
        } else {
            BodyText(stringResource(R.string.catalog_registry_no_description), muted = true)
        }
    }
    Section(stringResource(R.string.catalog_registry_section_mode)) {
        BodyText(modeText(agent.mode))
        if (agent.hidden) SmallChip(stringResource(R.string.catalog_registry_hidden))
    }
    Section(stringResource(R.string.catalog_registry_section_model)) {
        val model = agent.model
        if (model != null) {
            ModelLink(model) { onIntent(AgentDetailIntent.OpenModel) }
        } else {
            BodyText(stringResource(R.string.catalog_registry_agent_no_model), muted = true)
        }
    }
    Section(stringResource(R.string.catalog_registry_section_permissions)) {
        if (agent.permissions.isEmpty()) {
            BodyText(stringResource(R.string.catalog_registry_no_permissions), muted = true)
        } else {
            agent.permissions.forEachIndexed { index, rule ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PermissionRow(rule)
            }
        }
    }
}

/** The agent's model, with its variant when it names one; a tap opens the model's details. */
@Composable
private fun ModelLink(model: ModelRef, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhopencodeTheme.spacing.touchTarget)
            .clickable(
                onClickLabel = stringResource(R.string.catalog_registry_open_model),
                role = Role.Button,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.label(),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge,
                overflow = TextOverflow.Ellipsis,
            )
            model.variant?.let {
                Text(text = it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        Icon(
            imageVector = PhopencodeIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A rule: the tool or action, the resource it covers in monospace, and its effect; read as one. A
 * long resource wraps in its own column.
 */
@Composable
private fun PermissionRow(rule: PermissionRule) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = rule.action, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = rule.resource,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
        // An unknown effect is the server's word and can be long: the chip ellipsizes in at most
        // half the row, so the action and resource keep theirs.
        StatusChip(
            status = rule.effect.tone,
            label = effectText(rule.effect),
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private val PermissionEffect.tone: StatusTone
    get() = when (this) {
        PermissionEffect.Allow -> StatusTone.Success
        PermissionEffect.Deny -> StatusTone.Danger
        PermissionEffect.Ask -> StatusTone.Warning
        is PermissionEffect.Unknown -> StatusTone.Info
    }
