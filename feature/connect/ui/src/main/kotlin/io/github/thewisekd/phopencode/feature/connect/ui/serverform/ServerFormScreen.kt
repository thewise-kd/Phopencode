package io.github.thewisekd.phopencode.feature.connect.ui.serverform

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.Loading
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormAuthMethod
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormField
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormFieldError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormState
import io.github.thewisekd.phopencode.feature.connect.ui.R

/**
 * Adding or editing a server (P4.7). Draws [state] and sends [onIntent]; the presenter validates and
 * saves. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 *
 * The two password fields are the only text the screen holds itself, because the state never holds a
 * secret: they are masked, kept in memory only (never in saved state, so they do not outlive the
 * process or go into a saved bundle), and sent as typed with their intents.
 */
@Composable
fun ServerFormScreen(
    route: ConnectRoute.ServerForm,
    state: ServerFormState,
    onIntent: (ServerFormIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Above the loading branch, so a typed password stays while the auth method or loading changes.
    var loginPassword by remember { mutableStateOf("") }
    var opencodePassword by remember { mutableStateOf("") }
    // The field shows only what the presenter holds: once it no longer holds a typed password (after a
    // save, say), the field empties too. Keyed on the flag, so typing before the flag turns on is kept.
    LaunchedEffect(state.passwordEntered) {
        if (!state.passwordEntered) loginPassword = ""
    }
    LaunchedEffect(state.opencodePasswordEntered) {
        if (!state.opencodePasswordEntered) opencodePassword = ""
    }
    val title = stringResource(
        if (state.isNew) R.string.connect_serverform_title_new else R.string.connect_serverform_title_edit,
    )
    PhopencodeScaffold(
        title = title,
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(ServerFormIntent.Cancel) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            val error = state.error
            if (error != null) {
                ErrorBanner(
                    modifier = Modifier.padding(horizontal = PhopencodeTheme.spacing.lg),
                    message = stringResource(error.messageRes()),
                    onDismiss = { onIntent(ServerFormIntent.DismissError) },
                )
            }
            if (state.loading) {
                Loading(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    message = stringResource(R.string.connect_serverform_loading),
                )
            } else {
                ServerForm(
                    state = state,
                    onIntent = onIntent,
                    loginPassword = loginPassword,
                    onLoginPasswordChange = {
                        loginPassword = it
                        onIntent(ServerFormIntent.PasswordChanged(it))
                    },
                    opencodePassword = opencodePassword,
                    onOpencodePasswordChange = {
                        opencodePassword = it
                        onIntent(ServerFormIntent.OpencodePasswordChanged(it))
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ServerForm(
    state: ServerFormState,
    onIntent: (ServerFormIntent) -> Unit,
    loginPassword: String,
    onLoginPasswordChange: (String) -> Unit,
    opencodePassword: String,
    onOpencodePasswordChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    val enabled = !state.saving
    val save = { if (enabled) onIntent(ServerFormIntent.Save) }
    Column(
        modifier = modifier
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        FormTextField(
            value = state.name,
            onValueChange = { onIntent(ServerFormIntent.NameChanged(it)) },
            label = stringResource(R.string.connect_serverform_name),
            tag = ServerFormTags.NAME,
            error = state.fieldErrors[ServerFormField.Name]?.let { stringResource(fieldErrorRes(ServerFormField.Name, it)) },
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
        )
        FormTextField(
            value = state.host,
            onValueChange = { onIntent(ServerFormIntent.HostChanged(it)) },
            label = stringResource(R.string.connect_serverform_host),
            tag = ServerFormTags.HOST,
            error = state.fieldErrors[ServerFormField.Host]?.let { stringResource(fieldErrorRes(ServerFormField.Host, it)) },
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
        )
        FormTextField(
            value = state.port,
            onValueChange = { onIntent(ServerFormIntent.PortChanged(it)) },
            label = stringResource(R.string.connect_serverform_port),
            tag = ServerFormTags.PORT,
            error = state.fieldErrors[ServerFormField.Port]?.let { stringResource(fieldErrorRes(ServerFormField.Port, it)) },
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        )
        FormTextField(
            value = state.username,
            onValueChange = { onIntent(ServerFormIntent.UsernameChanged(it)) },
            label = stringResource(R.string.connect_serverform_username),
            tag = ServerFormTags.USERNAME,
            error = state.fieldErrors[ServerFormField.Username]?.let {
                stringResource(fieldErrorRes(ServerFormField.Username, it))
            },
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
            ),
        )

        AuthMethodChoice(state.authMethod, enabled, onIntent)

        when (state.authMethod) {
            ServerFormAuthMethod.Password -> FormTextField(
                value = loginPassword,
                onValueChange = onLoginPasswordChange,
                label = stringResource(R.string.connect_serverform_password),
                tag = ServerFormTags.PASSWORD_FIELD,
                error = state.fieldErrors[ServerFormField.Password]?.let {
                    stringResource(fieldErrorRes(ServerFormField.Password, it))
                },
                hint = when {
                    loginPassword.isNotEmpty() -> null
                    state.passwordEntered -> stringResource(R.string.connect_serverform_password_entered)
                    state.storedPassword -> stringResource(R.string.connect_serverform_password_keep)
                    else -> null
                },
                enabled = enabled,
                keyboardOptions = SECRET_KEYBOARD.copy(imeAction = ImeAction.Next),
                visualTransformation = PasswordVisualTransformation(),
            )
            ServerFormAuthMethod.Key -> KeyChoice(state, enabled, onIntent)
        }

        FormTextField(
            value = state.opencodePort,
            onValueChange = { onIntent(ServerFormIntent.OpencodePortChanged(it)) },
            label = stringResource(R.string.connect_serverform_opencode_port),
            tag = ServerFormTags.OPENCODE_PORT,
            error = state.fieldErrors[ServerFormField.OpencodePort]?.let {
                stringResource(fieldErrorRes(ServerFormField.OpencodePort, it))
            },
            hint = stringResource(R.string.connect_serverform_opencode_port_help),
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        )

        FormTextField(
            value = opencodePassword,
            onValueChange = onOpencodePasswordChange,
            label = stringResource(R.string.connect_serverform_opencode_password),
            tag = ServerFormTags.OPENCODE_PASSWORD_FIELD,
            error = null,
            hint = when {
                opencodePassword.isNotEmpty() -> null
                state.opencodePasswordEntered -> stringResource(R.string.connect_serverform_opencode_password_entered)
                state.storedOpencodePassword -> stringResource(R.string.connect_serverform_opencode_password_keep)
                else -> stringResource(R.string.connect_serverform_opencode_password_help)
            },
            enabled = enabled,
            keyboardOptions = SECRET_KEYBOARD.copy(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            visualTransformation = PasswordVisualTransformation(),
        )
        if (state.storedOpencodePassword) {
            TextButton(
                onClick = {
                    // A typed replacement goes too, so the field never shows a password nobody will save.
                    if (opencodePassword.isNotEmpty() || state.opencodePasswordEntered) onOpencodePasswordChange("")
                    onIntent(ServerFormIntent.ClearOpencodePassword)
                },
                enabled = enabled,
                modifier = Modifier.testTag(ServerFormTags.CLEAR_OPENCODE_PASSWORD_BUTTON),
            ) {
                Text(stringResource(R.string.connect_serverform_opencode_password_clear))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onIntent(ServerFormIntent.Cancel) },
                modifier = Modifier.testTag(ServerFormTags.CANCEL),
            ) {
                Text(stringResource(R.string.connect_serverform_cancel))
            }
            Button(
                onClick = save,
                enabled = enabled,
                modifier = Modifier.testTag(ServerFormTags.SAVE),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(SAVING_INDICATOR_SIZE).padding(end = spacing.xs),
                        color = LocalContentColor.current,
                        strokeWidth = 2.dp,
                    )
                    Text(stringResource(R.string.connect_serverform_saving))
                } else {
                    Text(stringResource(R.string.connect_serverform_save))
                }
            }
        }
    }
}

/** A secret: no suggestions, no learning, a keyboard that knows it is a password. */
private val SECRET_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
)

private val SAVING_INDICATOR_SIZE = 20.dp

@Composable
private fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
    error: String?,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    hint: String? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val supporting = error ?: hint
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().testTag(tag),
        enabled = enabled,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        isError = error != null,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
    )
}

@Composable
private fun AuthMethodChoice(
    selected: ServerFormAuthMethod,
    enabled: Boolean,
    onIntent: (ServerFormIntent) -> Unit,
) {
    Text(
        text = stringResource(R.string.connect_serverform_auth),
        modifier = Modifier.padding(top = PhopencodeTheme.spacing.sm).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
    )
    Column(Modifier.selectableGroup()) {
        ChoiceRow(
            label = stringResource(R.string.connect_serverform_auth_password),
            selected = selected == ServerFormAuthMethod.Password,
            enabled = enabled,
            tag = ServerFormTags.AUTH_PASSWORD_OPTION,
            onClick = { onIntent(ServerFormIntent.AuthMethodChanged(ServerFormAuthMethod.Password)) },
        )
        ChoiceRow(
            label = stringResource(R.string.connect_serverform_auth_key),
            selected = selected == ServerFormAuthMethod.Key,
            enabled = enabled,
            tag = ServerFormTags.AUTH_KEY_OPTION,
            onClick = { onIntent(ServerFormIntent.AuthMethodChanged(ServerFormAuthMethod.Key)) },
        )
    }
}

/** The keys to log in with, from the state's list, and the way to add one. */
@Composable
private fun KeyChoice(state: ServerFormState, enabled: Boolean, onIntent: (ServerFormIntent) -> Unit) {
    if (state.keys.isEmpty()) {
        Text(
            text = stringResource(R.string.connect_serverform_keys_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Column(Modifier.selectableGroup()) {
            state.keys.forEach { key ->
                ChoiceRow(
                    label = key.name,
                    detail = stringResource(R.string.connect_serverform_key_detail, key.type, key.fingerprint),
                    selected = key.id == state.selectedKey,
                    enabled = enabled,
                    tag = ServerFormTags.key(key.id),
                    onClick = { onIntent(ServerFormIntent.KeySelected(key.id)) },
                )
            }
        }
    }
    val keyError = state.fieldErrors[ServerFormField.Key]?.let { stringResource(fieldErrorRes(ServerFormField.Key, it)) }
    if (keyError != null) {
        Text(
            text = keyError,
            modifier = Modifier.semantics { error(keyError) },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    TextButton(
        onClick = { onIntent(ServerFormIntent.ManageKeys) },
        enabled = enabled,
        modifier = Modifier.testTag(ServerFormTags.MANAGE_KEYS),
    ) {
        Text(stringResource(R.string.connect_serverform_manage_keys))
    }
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

@StringRes
private fun ServerFormError.messageRes(): Int = when (this) {
    ServerFormError.LoadFailed -> R.string.connect_serverform_error_load_failed
    ServerFormError.NotFound -> R.string.connect_serverform_error_not_found
    ServerFormError.SaveFailed -> R.string.connect_serverform_error_save_failed
}

/** The message of [error] on [field]: each pair has its own text, so it can say what to fix. */
@StringRes
private fun fieldErrorRes(field: ServerFormField, error: ServerFormFieldError): Int {
    val (required, invalid) = when (field) {
        ServerFormField.Name -> R.string.connect_serverform_error_name_required to R.string.connect_serverform_error_name_invalid
        ServerFormField.Host -> R.string.connect_serverform_error_host_required to R.string.connect_serverform_error_host_invalid
        ServerFormField.Port -> R.string.connect_serverform_error_port_required to R.string.connect_serverform_error_port_invalid
        ServerFormField.Username ->
            R.string.connect_serverform_error_username_required to R.string.connect_serverform_error_username_invalid
        ServerFormField.Password ->
            R.string.connect_serverform_error_password_required to R.string.connect_serverform_error_password_invalid
        ServerFormField.Key -> R.string.connect_serverform_error_key_required to R.string.connect_serverform_error_key_invalid
        // Never required: an empty opencode port means automatic detection.
        ServerFormField.OpencodePort ->
            R.string.connect_serverform_error_opencode_port_invalid to R.string.connect_serverform_error_opencode_port_invalid
    }
    return when (error) {
        ServerFormFieldError.Required -> required
        ServerFormFieldError.Invalid -> invalid
    }
}
