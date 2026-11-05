package io.github.thewisekd.phopencode.feature.chat.ui.switcher

import android.view.Gravity
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.AgentOption
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ModelOption
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState
import io.github.thewisekd.phopencode.feature.chat.ui.R

/** How faint the control's text is while it cannot be tapped. */
private const val DISABLED_ALPHA = 0.6f

/** The compact control's least height. */
private val CONTROL_MIN_HEIGHT = 36.dp

/** The most of the control's width the agent's and the variant's names take; the model takes the rest. */
private const val AGENT_MAX_SHARE = 0.35f
private const val VARIANT_MAX_SHARE = 0.25f

/** The most of the screen's height the sheet takes; its content scrolls beyond that. */
private const val SHEET_MAX_HEIGHT = 0.85f

/**
 * The session's agent, model and variant (P7.11), drawn from [state]; every tap sends a
 * [SwitcherIntent] to [onIntent], and what shows next comes only from the state.
 *
 * - **The control:** a compact row above the composer with the current agent, model and variant,
 *   by the names the lists give them, or as the server gives them when the lists do not name them.
 *   Tapping it sends [SwitcherIntent.Open]; it cannot be tapped until `SwitcherState.enabled`.
 * - **The sheet:** a modal sheet at the bottom of the screen, drawn while `SwitcherState.open`,
 *   that lists the agents and the models, each a radio choice with the current one selected; a
 *   model with variants offers them, and its default, as chips with the current one selected.
 *   Closing it, by its button, back or a tap outside, sends [SwitcherIntent.Close].
 * - **On its way:** while the lists load or a switch is sent, a still note says so; every choice is
 *   disabled while `SwitcherState.canSwitch` does not hold.
 * - **Failed:** what failed and why, dismissable; a failed load can be retried.
 *
 * Nothing animates; the sheet appears at once. The server's names are drawn as they are and never
 * logged.
 */
@Composable
fun AgentModelSwitcher(
    state: SwitcherState,
    onIntent: (SwitcherIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Control(state, onIntent)
        if (state.open) {
            Sheet(state, onIntent)
        }
    }
}

@Composable
private fun Control(state: SwitcherState, onIntent: (SwitcherIntent) -> Unit) {
    val agent = state.agent?.let { id -> state.agents.firstOrNull { it.id == id }?.name ?: id }
        ?: stringResource(R.string.chat_switcher_agent_default)
    val model = state.model?.let { ref -> state.models.firstOrNull { it.isCurrent(ref) }?.name ?: "${ref.providerId}/${ref.modelId}" }
        ?: stringResource(R.string.chat_switcher_model_default)
    val variant = state.model?.variant
    val color = MaterialTheme.colorScheme.onSurfaceVariant.let { if (state.enabled) it else it.copy(alpha = DISABLED_ALPHA) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The agent and the variant each get at most a share of the width and the model the rest,
        // so a long name ellipsizes instead of pushing another part out of view.
        val agentMax = maxWidth * AGENT_MAX_SHARE
        val variantMax = maxWidth * VARIANT_MAX_SHARE
        ControlRow(state, onIntent) {
            Part(agent, stringResource(R.string.chat_switcher_agent_label, agent), color, SWITCHER_AGENT_TAG, Modifier.widthIn(max = agentMax))
            Part(model, stringResource(R.string.chat_switcher_model_label, model), color, SWITCHER_MODEL_TAG, Modifier.weight(1f, fill = false))
            variant?.let {
                Part(it, stringResource(R.string.chat_switcher_variant_label, it), color, SWITCHER_VARIANT_TAG, Modifier.widthIn(max = variantMax))
            }
        }
    }
}

@Composable
private fun ControlRow(state: SwitcherState, onIntent: (SwitcherIntent) -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Compact, to leave the timeline its room, but full width, so it is easy to hit.
            .heightIn(min = CONTROL_MIN_HEIGHT)
            .testTag(SWITCHER_CONTROL_TAG)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                enabled = state.enabled,
                onClickLabel = stringResource(R.string.chat_switcher_change),
                role = Role.Button,
            ) { onIntent(SwitcherIntent.Open) }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One part of the control: [text] on screen, [description] for a screen reader. */
@Composable
private fun Part(text: String, description: String, color: Color, tag: String, modifier: Modifier) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.testTag(tag).semantics { contentDescription = description },
    )
}

/**
 * A modal sheet at the bottom of the screen: a dialog window pinned to the bottom edge, full width,
 * with the sheet's top corners. Back and a tap outside close it, as does its button.
 *
 * Not Material's `ModalBottomSheet`: under Robolectric the first test in a run that shows one never
 * comes to rest, which would hang the app's idle and language tests by order. This one does not
 * animate at all, so it is the same in every run.
 */
@Composable
private fun Sheet(state: SwitcherState, onIntent: (SwitcherIntent) -> Unit) {
    Dialog(
        onDismissRequest = { onIntent(SwitcherIntent.Close) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setGravity(Gravity.BOTTOM) }
        val maxHeight = LocalConfiguration.current.screenHeightDp.dp * SHEET_MAX_HEIGHT
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
            shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
        ) {
            SheetContent(state, onIntent)
        }
    }
}

@Composable
private fun SheetContent(state: SwitcherState, onIntent: (SwitcherIntent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SWITCHER_SHEET_TAG)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.chat_switcher_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).testTag(SWITCHER_TITLE_TAG).semantics { heading() },
            )
            TextButton(
                onClick = { onIntent(SwitcherIntent.Close) },
                modifier = Modifier.testTag(SWITCHER_CLOSE_TAG),
            ) {
                Text(stringResource(R.string.chat_switcher_close))
            }
        }
        state.error?.let { error ->
            ErrorBanner(
                modifier = Modifier.testTag(SWITCHER_ERROR_TAG),
                message = errorText(error),
                onRetry = if (error.action == SwitcherAction.Load) ({ onIntent(SwitcherIntent.Retry) }) else null,
                onDismiss = { onIntent(SwitcherIntent.DismissError) },
            )
        }
        if (state.loading) Note(R.string.chat_switcher_loading, SWITCHER_LOADING_TAG)
        if (state.switching) Note(R.string.chat_switcher_switching, SWITCHER_SWITCHING_TAG)
        // An empty list says so only once it is known to be empty: loaded, and not failed.
        val listed = !state.loading && state.error?.action != SwitcherAction.Load
        Agents(state, listed, onIntent)
        Models(state, listed, onIntent)
        // The bottom gap is a child, so nothing is padded after the scroll.
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Note(@StringRes text: Int, tag: String) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun SectionTitle(@StringRes text: Int, tag: String) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp).testTag(tag).semantics { heading() },
    )
}

@Composable
private fun Agents(state: SwitcherState, listed: Boolean, onIntent: (SwitcherIntent) -> Unit) {
    SectionTitle(R.string.chat_switcher_agents, SWITCHER_AGENTS_TAG)
    if (state.agents.isEmpty()) {
        if (listed) Note(R.string.chat_switcher_no_agents, SWITCHER_NO_AGENTS_TAG)
        return
    }
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        state.agents.forEach { agent -> AgentRow(agent, agent.id == state.agent, state.canSwitch, onIntent) }
    }
}

@Composable
private fun AgentRow(agent: AgentOption, selected: Boolean, enabled: Boolean, onIntent: (SwitcherIntent) -> Unit) {
    Choice(
        name = agent.name,
        detail = agent.description?.takeIf { it.isNotBlank() },
        selected = selected,
        enabled = enabled,
        tag = switcherAgentTag(agent.id),
    ) { onIntent(SwitcherIntent.PickAgent(agent.id)) }
}

@Composable
private fun Models(state: SwitcherState, listed: Boolean, onIntent: (SwitcherIntent) -> Unit) {
    SectionTitle(R.string.chat_switcher_models, SWITCHER_MODELS_TAG)
    if (state.models.isEmpty()) {
        if (listed) Note(R.string.chat_switcher_no_models, SWITCHER_NO_MODELS_TAG)
        return
    }
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        state.models.forEach { model -> ModelRow(model, state.model, state.canSwitch, onIntent) }
    }
}

@Composable
private fun ModelRow(model: ModelOption, current: ModelRef?, enabled: Boolean, onIntent: (SwitcherIntent) -> Unit) {
    val isCurrent = model.isCurrent(current)
    Choice(
        name = model.name,
        detail = model.providerId,
        selected = isCurrent,
        enabled = enabled,
        tag = switcherModelTag(model.providerId, model.modelId),
        // The current model's row keeps its variant: picking it again changes nothing.
    ) { onIntent(SwitcherIntent.PickModel(model.ref(if (isCurrent) current?.variant else null))) }
    if (model.variants.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(start = 48.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (listOf<String?>(null) + model.variants).forEach { variant ->
                FilterChip(
                    selected = isCurrent && current?.variant == variant,
                    onClick = { onIntent(SwitcherIntent.PickModel(model.ref(variant))) },
                    enabled = enabled,
                    label = { Text(variant ?: stringResource(R.string.chat_switcher_variant_default)) },
                    modifier = Modifier.testTag(switcherVariantTag(model.providerId, model.modelId, variant)),
                )
            }
        }
    }
}

/** A radio choice: [name] above [detail], selected when it is current. */
@Composable
private fun Choice(
    name: String,
    detail: String?,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun errorText(error: SwitcherError): String {
    val res = when (error.action) {
        SwitcherAction.Load -> R.string.chat_switcher_error_load
        SwitcherAction.SwitchAgent -> R.string.chat_switcher_error_agent
        SwitcherAction.SwitchModel -> R.string.chat_switcher_error_model
    }
    return stringResource(res, stringResource(error.reason.reasonRes()))
}

@StringRes
private fun ChatFailure.reasonRes(): Int = when (this) {
    ChatFailure.NotConnected -> R.string.chat_switcher_reason_not_connected
    ChatFailure.Unreachable -> R.string.chat_switcher_reason_unreachable
    ChatFailure.Unauthorized -> R.string.chat_switcher_reason_unauthorized
    ChatFailure.NotFound -> R.string.chat_switcher_reason_not_found
    ChatFailure.Conflict -> R.string.chat_switcher_reason_conflict
    ChatFailure.Rejected -> R.string.chat_switcher_reason_rejected
    ChatFailure.ServerFailed -> R.string.chat_switcher_reason_server_failed
    ChatFailure.Unknown -> R.string.chat_switcher_reason_unknown
}
