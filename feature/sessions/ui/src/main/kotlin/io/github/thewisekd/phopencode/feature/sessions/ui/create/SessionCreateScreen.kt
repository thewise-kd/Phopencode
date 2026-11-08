package io.github.thewisekd.phopencode.feature.sessions.ui.create

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.CreateFolderError
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import io.github.thewisekd.phopencode.feature.sessions.ui.R

/**
 * Creating a session (P5.5). Draws [state] and sends [onIntent]; the presenter validates, creates and
 * then opens the chat, which `SessionsGraph` turns into navigation.
 *
 * The fields are grouped in rounded cards with short titles: the folder, typed or picked from the
 * recent folders; the details, an optional title; then, once the catalog is loaded, the agent, picked
 * from a radio group, and the model, from a dialog, each with a way back to the server's choice.
 * "Start chat", the one filled button, sits under the form, always in reach, and is enabled only
 * when [SessionCreateState.canCreate].
 *
 * Folders and titles are user data: the screen shows them and sends them with their intents, and
 * nothing else. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun SessionCreateScreen(
    route: SessionsRoute.Create,
    state: SessionCreateState,
    onIntent: (SessionCreateIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val create = { if (state.canCreate) onIntent(SessionCreateIntent.Create) }
    PhopencodeScaffold(
        title = stringResource(R.string.sessions_create_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(SessionCreateIntent.Cancel) },
        bottomBar = { CreateBar(enabled = state.canCreate, creating = state.creating, onCreate = create) },
    ) { padding ->
        CreateForm(
            state = state,
            onIntent = onIntent,
            onCreate = create,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        )
    }
}

@Composable
private fun CreateForm(
    state: SessionCreateState,
    onIntent: (SessionCreateIntent) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    val enabled = !state.creating
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        state.error?.let { failure ->
            ErrorBanner(
                message = stringResource(R.string.sessions_create_error_create, stringResource(failure.reasonRes)),
                onDismiss = { onIntent(SessionCreateIntent.DismissError) },
            )
        }
        state.catalogError?.let { failure ->
            ErrorBanner(
                message = stringResource(R.string.sessions_create_error_catalog, stringResource(failure.reasonRes)),
                onRetry = { onIntent(SessionCreateIntent.Retry) },
            )
        }

        FormCard(R.string.sessions_create_section_folder) {
            FolderField(state.directory, state.folderError, enabled, onIntent)
            RecentFolders(state.recentFolders, enabled, onIntent)
        }

        FormCard(R.string.sessions_create_section_details) {
            TitleField(state, enabled, onIntent, onCreate)
            if (state.skillId != null) {
                Text(
                    text = stringResource(R.string.sessions_create_skill),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        when {
            state.loading -> LoadingCatalog()
            state.catalogError == null -> {
                FormCard(R.string.sessions_create_agent) { AgentChoice(state, enabled, onIntent) }
                FormCard(R.string.sessions_create_model) { ModelChoice(state, enabled, onIntent) }
            }
        }
    }
}

@Composable
private fun TitleField(
    state: SessionCreateState,
    enabled: Boolean,
    onIntent: (SessionCreateIntent) -> Unit,
    onCreate: () -> Unit,
) {
    OutlinedTextField(
        value = state.title,
        onValueChange = { onIntent(SessionCreateIntent.TitleChanged(it)) },
        modifier = Modifier.fillMaxWidth().testTag(SessionCreateTags.TITLE),
        enabled = enabled,
        label = { Text(stringResource(R.string.sessions_create_session_title)) },
        supportingText = { Text(stringResource(R.string.sessions_create_session_title_help)) },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onCreate() }),
        singleLine = true,
    )
}

@Composable
private fun FolderField(
    directory: String,
    folderError: CreateFolderError?,
    enabled: Boolean,
    onIntent: (SessionCreateIntent) -> Unit,
) {
    val message = folderError?.let { stringResource(it.messageRes) }
    OutlinedTextField(
        value = directory,
        onValueChange = { onIntent(SessionCreateIntent.FolderChanged(it)) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SessionCreateTags.FOLDER)
            .semantics { if (message != null) error(message) },
        enabled = enabled,
        label = { Text(stringResource(R.string.sessions_create_folder)) },
        supportingText = { Text(message ?: stringResource(R.string.sessions_create_folder_help)) },
        isError = message != null,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        ),
        singleLine = true,
    )
}

/** The active server's recent folders, newest first; a tap puts one in the folder field. */
@Composable
private fun RecentFolders(recent: List<String>, enabled: Boolean, onIntent: (SessionCreateIntent) -> Unit) {
    if (recent.isEmpty()) return
    SubHeading(R.string.sessions_create_recent)
    val selectLabel = stringResource(R.string.sessions_create_recent_select)
    Column {
        recent.forEach { path ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PhopencodeTheme.spacing.touchTarget)
                    .clickable(enabled = enabled, onClickLabel = selectLabel, role = Role.Button) {
                        onIntent(SessionCreateIntent.FolderChanged(path))
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.md),
                ) {
                    Icon(
                        imageVector = PhopencodeIcons.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // A long path loses its start, not its end: the last folders tell paths apart.
                    Text(
                        text = path,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/** The agents to start with, and the server's choice first. */
@Composable
private fun AgentChoice(state: SessionCreateState, enabled: Boolean, onIntent: (SessionCreateIntent) -> Unit) {
    Column(Modifier.selectableGroup()) {
        ChoiceRow(
            label = stringResource(R.string.sessions_create_agent_default),
            selected = state.selectedAgent == null,
            enabled = enabled,
            tag = SessionCreateTags.AGENT_DEFAULT,
            onClick = { onIntent(SessionCreateIntent.AgentSelected(null)) },
        )
        state.agents.forEach { agent ->
            ChoiceRow(
                label = agent.name,
                detail = agent.description,
                selected = state.selectedAgent == agent.id,
                enabled = enabled,
                tag = SessionCreateTags.agent(agent.id),
                onClick = { onIntent(SessionCreateIntent.AgentSelected(agent.id)) },
            )
        }
    }
}

/**
 * The model in use, a tap away from a dialog that lists them all: a server can offer many models, too
 * many for the form itself. Whether the dialog is open is the screen's own state; the pick is not.
 */
@Composable
private fun ModelChoice(state: SessionCreateState, enabled: Boolean, onIntent: (SessionCreateIntent) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val defaultLabel = state.defaultModel
        ?.let { stringResource(R.string.sessions_create_model_default_named, state.models.labelOf(it)) }
        ?: stringResource(R.string.sessions_create_model_default)
    val current = state.selectedModel?.let { state.models.labelOf(it) } ?: defaultLabel

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhopencodeTheme.spacing.touchTarget)
            .clickable(
                enabled = enabled,
                onClickLabel = stringResource(R.string.sessions_create_model_change),
                role = Role.Button,
            ) { picking = true }
            .testTag(SessionCreateTags.MODEL),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = current,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = PhopencodeIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (picking && enabled) {
        ModelDialog(
            models = state.models,
            selected = state.selectedModel,
            defaultLabel = defaultLabel,
            onPick = { ref ->
                picking = false
                onIntent(SessionCreateIntent.ModelSelected(ref))
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun ModelDialog(
    models: List<Model>,
    selected: ModelRef?,
    defaultLabel: String,
    onPick: (ModelRef?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessions_create_model_choose)) },
        text = {
            LazyColumn(Modifier.selectableGroup()) {
                item(key = "default") {
                    ChoiceRow(
                        label = defaultLabel,
                        selected = selected == null,
                        enabled = true,
                        tag = SessionCreateTags.MODEL_DEFAULT,
                        onClick = { onPick(null) },
                    )
                }
                items(models, key = { "${it.providerId}/${it.id}" }) { model ->
                    val ref = ModelRef(providerId = model.providerId, modelId = model.id)
                    ChoiceRow(
                        label = model.name,
                        detail = ref.display,
                        selected = selected?.sameModelAs(ref) == true,
                        enabled = true,
                        tag = SessionCreateTags.model(ref),
                        onClick = { onPick(ref) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.sessions_create_model_cancel)) }
        },
    )
}

/** The catalog's name for [ref], or the reference itself when the catalog does not list it. */
private fun List<Model>.labelOf(ref: ModelRef): String =
    firstOrNull { it.providerId == ref.providerId && it.id == ref.modelId }?.name ?: ref.display

private val ModelRef.display: String
    get() = "$providerId/$modelId"

/** The same model, whatever the variant: the picker lists models, not variants. */
private fun ModelRef.sameModelAs(other: ModelRef): Boolean = providerId == other.providerId && modelId == other.modelId

/** "Start chat", always in reach under the form. */
@Composable
private fun CreateBar(enabled: Boolean, creating: Boolean, onCreate: () -> Unit) {
    Surface(tonalElevation = PhopencodeTheme.spacing.xxs) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.sm),
        ) {
            // The keyboard pushes the bar up, so the button stays in reach while typing.
            // No spinner while creating: an endless animation keeps Robolectric's idle() from returning.
            Button(
                onClick = onCreate,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PhopencodeTheme.spacing.touchTarget)
                    .testTag(SessionCreateTags.CREATE),
            ) {
                Text(
                    stringResource(if (creating) R.string.sessions_create_creating else R.string.sessions_create_create),
                )
            }
        }
    }
}

/** A group of the form: a rounded card with a short title, a heading for TalkBack. */
@Composable
private fun FormCard(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(title),
            modifier = Modifier.fillMaxWidth().semantics { heading() },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
        )
        content()
    }
}

/** A title inside a card, such as the recent folders under the folder field. */
@Composable
private fun SubHeading(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = PhopencodeTheme.spacing.xs)
            .semantics { heading() },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** One choice of a group: the whole row is the touch target, at least 48dp tall. */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
    detail: String? = null,
) {
    val spacing = PhopencodeTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(vertical = spacing.xs)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    text = detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * The catalog is loading. A still message rather than core/ui's `Loading`: its spinner never stops,
 * and Robolectric's `idle()` in the app's ActivityScenario tests, which open this screen while the
 * presenter loads, would never return. TalkBack hears the same as for `Loading`: the message, an
 * indeterminate progress and a polite live region.
 */
@Composable
private fun LoadingCatalog() {
    val message = stringResource(R.string.sessions_create_loading)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            }
            .padding(PhopencodeTheme.spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@get:StringRes
private val CreateFolderError.messageRes: Int
    get() = when (this) {
        CreateFolderError.Missing -> R.string.sessions_create_folder_error_missing
        CreateFolderError.NotAbsolute -> R.string.sessions_create_folder_error_not_absolute
    }

@get:StringRes
private val SessionsFailure.reasonRes: Int
    get() = when (this) {
        SessionsFailure.NotConnected -> R.string.sessions_create_reason_not_connected
        SessionsFailure.Unreachable -> R.string.sessions_create_reason_unreachable
        SessionsFailure.Unauthorized -> R.string.sessions_create_reason_unauthorized
        SessionsFailure.NotFound -> R.string.sessions_create_reason_not_found
        SessionsFailure.Conflict -> R.string.sessions_create_reason_conflict
        SessionsFailure.Rejected -> R.string.sessions_create_reason_rejected
        SessionsFailure.ServerFailed -> R.string.sessions_create_reason_server_failed
        SessionsFailure.Unknown -> R.string.sessions_create_reason_unknown
    }
