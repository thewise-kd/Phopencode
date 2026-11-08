package io.github.thewisekd.phopencode.feature.chat.ui.palette

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.BuiltInCommand
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.BuiltInNotice
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteItem
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteTrigger
import io.github.thewisekd.phopencode.feature.chat.ui.R

/** The smallest touch target the platform asks for. */
private val MIN_TOUCH = 48.dp

/**
 * How high the rows may grow before they scroll: about five rows, so the timeline above keeps room
 * on a phone. The chat places the palette in a plain column, so the bound has to come from here.
 */
private val LIST_MAX_HEIGHT = 280.dp

private const val NAME_LINES = 2
private const val DESCRIPTION_LINES = 2

/**
 * The command palette over the composer, open while a `/` or `@` word is typed: a title for the
 * trigger with a close button that sends [PaletteIntent.Dismiss], then the matching items, each
 * with its kind in words (command, built-in, skill, agent, file or folder) so they are told apart
 * without color. A built-in's row also names its aliases. A tap on a row sends
 * [PaletteIntent.Pick]; rows are disabled while a command runs.
 *
 * Why the last built-in did not run ([PaletteState.notice]) shows as a note under the title, read
 * out when it appears, with a button that sends [PaletteIntent.DismissNotice]. While the palette
 * is closed, as after sending a command with text, the notice and a failure still show, on their
 * own.
 *
 * A failed list shows its reason with a retry ([PaletteIntent.Retry]); a failed command its reason
 * only. Both can be dismissed ([PaletteIntent.DismissError]). Loading and running are lines of
 * text, never a spinner, so the screen comes to rest.
 *
 * The rows scroll inside a bounded height of their own; the chat's column around them does not
 * scroll. Names, descriptions and paths are drawn, never logged. Draws nothing while closed but
 * the notice and the failure, if any: only an empty box for [modifier], so the chat's slot stays
 * in place.
 */
@Composable
fun CommandPalette(
    state: PaletteState,
    onIntent: (PaletteIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trigger = state.trigger
    val notice = state.notice
    if (trigger == null) {
        if (notice == null && state.error == null) {
            Box(modifier)
        } else {
            Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                Column {
                    HorizontalDivider()
                    notice?.let { NoticeRow(it, onDismiss = { onIntent(PaletteIntent.DismissNotice) }) }
                    state.error?.let { Failure(it, onIntent) }
                }
            }
        }
        return
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column {
            HorizontalDivider()
            Header(trigger, onClose = { onIntent(PaletteIntent.Dismiss) })
            notice?.let { NoticeRow(it, onDismiss = { onIntent(PaletteIntent.DismissNotice) }) }
            state.error?.let { Failure(it, onIntent) }
            if (state.running) Note(stringResource(R.string.chat_palette_running))
            Column(
                // The scroll comes last: padding after it would make scrolling a row into view loop.
                Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT).verticalScroll(rememberScrollState()),
            ) {
                state.items.forEach { item ->
                    key(PaletteTags.item(item)) {
                        PaletteRow(item, enabled = !state.running, onPick = { onIntent(PaletteIntent.Pick(item)) })
                    }
                }
                when {
                    state.loading -> Note(stringResource(R.string.chat_palette_loading))
                    state.items.isEmpty() && state.error == null -> Note(stringResource(R.string.chat_palette_empty))
                }
            }
        }
    }
}

@Composable
private fun Header(trigger: PaletteTrigger, onClose: () -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                when (trigger) {
                    PaletteTrigger.Slash -> R.string.chat_palette_title_slash
                    PaletteTrigger.At -> R.string.chat_palette_title_at
                },
            ),
            modifier = Modifier.weight(1f).testTag(PaletteTags.TITLE).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
        )
        IconButton(onClick = onClose, modifier = Modifier.testTag(PaletteTags.CLOSE)) {
            Icon(imageVector = PaletteIcons.Close, contentDescription = stringResource(R.string.chat_palette_close))
        }
    }
}

/**
 * One item, read as one: its name, a skill's `/id` when that is not its name (what a sent draft
 * takes), its description, then its kind.
 */
@Composable
private fun PaletteRow(item: PaletteItem, enabled: Boolean, onPick: () -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val runs = item is PaletteItem.Command || item is PaletteItem.BuiltIn
    val clickLabel = stringResource(if (runs) R.string.chat_palette_run else R.string.chat_palette_attach)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH)
            .clickable(enabled = enabled, onClickLabel = clickLabel, role = Role.Button, onClick = onPick)
            .testTag(PaletteTags.item(item))
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(
                text = item.displayName(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = NAME_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            if (item is PaletteItem.Skill && item.id != item.name) {
                Text(
                    text = "/${item.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.description()?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = DESCRIPTION_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item is PaletteItem.BuiltIn && item.command.aliases.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.chat_palette_builtin_aliases,
                        item.command.aliases.joinToString(", ") { "/$it" },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        KindLabel(stringResource(item.kindRes()))
    }
}

/** A kind in words on a tinted label; the words carry the meaning. */
@Composable
internal fun KindLabel(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = PhopencodeTheme.spacing.sm, vertical = PhopencodeTheme.spacing.xxs),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

/** A failed list, with its retry, or a failed command; either can be dismissed. */
@Composable
private fun Failure(error: PaletteError, onIntent: (PaletteIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    ErrorBanner(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.sm, vertical = spacing.xs),
        message = errorText(error),
        onRetry = if (error.action == PaletteAction.Load) ({ onIntent(PaletteIntent.Retry) }) else null,
        onDismiss = { onIntent(PaletteIntent.DismissError) },
    )
}

/** Why a built-in did not run, read out as it appears, with a button that dismisses it. */
@Composable
private fun NoticeRow(notice: BuiltInNotice, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = PhopencodeTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(notice.textRes()),
            modifier = Modifier
                .weight(1f)
                .testTag(PaletteTags.NOTICE)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
        )
        IconButton(onClick = onDismiss, modifier = Modifier.testTag(PaletteTags.NOTICE_DISMISS)) {
            Icon(imageVector = PaletteIcons.Close, contentDescription = stringResource(R.string.chat_palette_notice_dismiss))
        }
    }
}

@StringRes
private fun BuiltInNotice.textRes(): Int = when (this) {
    BuiltInNotice.Unavailable -> R.string.chat_palette_notice_unavailable
    BuiltInNotice.NothingToUndo -> R.string.chat_palette_notice_nothing_to_undo
    BuiltInNotice.NothingToRedo -> R.string.chat_palette_notice_nothing_to_redo
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.md),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun PaletteItem.displayName(): String = when (this) {
    is PaletteItem.Command -> "/$name"
    is PaletteItem.BuiltIn -> "/${command.primary}"
    is PaletteItem.Skill -> name
    is PaletteItem.Agent -> name
    is PaletteItem.File -> path
}

@Composable
private fun PaletteItem.description(): String? = when (this) {
    is PaletteItem.Command -> description
    is PaletteItem.Skill -> description
    is PaletteItem.Agent -> description
    is PaletteItem.File -> null
    is PaletteItem.BuiltIn -> stringResource(command.descriptionRes())
}?.takeIf { it.isNotBlank() }

@StringRes
private fun BuiltInCommand.descriptionRes(): Int = when (this) {
    BuiltInCommand.New -> R.string.chat_palette_builtin_new
    BuiltInCommand.Compact -> R.string.chat_palette_builtin_compact
    BuiltInCommand.Fork -> R.string.chat_palette_builtin_fork
    BuiltInCommand.Undo -> R.string.chat_palette_builtin_undo
    BuiltInCommand.Redo -> R.string.chat_palette_builtin_redo
    BuiltInCommand.Models -> R.string.chat_palette_builtin_models
    BuiltInCommand.Agents -> R.string.chat_palette_builtin_agents
    BuiltInCommand.Sessions -> R.string.chat_palette_builtin_sessions
}

@StringRes
private fun PaletteItem.kindRes(): Int = when (this) {
    is PaletteItem.Command -> R.string.chat_palette_kind_command
    is PaletteItem.BuiltIn -> R.string.chat_palette_kind_builtin
    is PaletteItem.Skill -> R.string.chat_palette_kind_skill
    is PaletteItem.Agent -> R.string.chat_palette_kind_agent
    is PaletteItem.File -> if (directory) R.string.chat_palette_kind_folder else R.string.chat_palette_kind_file
}

@Composable
private fun errorText(error: PaletteError): String {
    val reason = stringResource(error.reason.reasonRes())
    return when (error.action) {
        PaletteAction.Load -> stringResource(R.string.chat_palette_error_load, reason)
        PaletteAction.RunCommand -> stringResource(R.string.chat_palette_error_command, reason)
    }
}

@StringRes
private fun ChatFailure.reasonRes(): Int = when (this) {
    ChatFailure.NotConnected -> R.string.chat_palette_reason_not_connected
    ChatFailure.Unreachable -> R.string.chat_palette_reason_unreachable
    ChatFailure.Unauthorized -> R.string.chat_palette_reason_unauthorized
    ChatFailure.NotFound -> R.string.chat_palette_reason_not_found
    ChatFailure.Conflict -> R.string.chat_palette_reason_conflict
    ChatFailure.Rejected -> R.string.chat_palette_reason_rejected
    ChatFailure.ServerFailed -> R.string.chat_palette_reason_server
    ChatFailure.Unknown -> R.string.chat_palette_reason_unknown
}

/** The palette's icons, drawn here: the module does not take the icon library. */
internal object PaletteIcons {
    val Close: ImageVector = ImageVector.Builder(
        name = "Palette.Close",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(
            "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}
