package io.github.thewisekd.phopencode.feature.connect.ui.keys

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.ui.R as CoreUiR
import io.github.thewisekd.phopencode.core.ui.component.ConfirmDialog
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.Loading
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysState
import io.github.thewisekd.phopencode.feature.connect.ui.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val PASSPHRASE_FIELD_TAG = "keys:passphrase"
internal const val GENERATE_NAME_TAG = "keys:generate:name"
internal const val GENERATE_PASSPHRASE_TAG = "keys:generate:passphrase"

/** The read of the latest picked file, if one runs. */
private class ImportJobSlot {
    var value: Job? = null
}

/** Private key files have no registered MIME type, so the picker offers every file. */
private val KEY_FILE_TYPES = arrayOf("*/*")

/**
 * The user's SSH keys: each one's name, type and SHA-256 fingerprint, never any part of a key.
 * Draws [state] and sends [onIntent]; the presenter decides everything.
 *
 * An import opens the system file picker, reads the chosen file off the main thread, at most
 * [MAX_KEY_FILE_BYTES], and sends it as [KeysIntent.Import] without keeping a copy. A file that
 * cannot be read is reported here, as the presenter never sees it. Passphrases are typed into masked
 * fields, kept only while their dialog is open and never in saved state, and sent only in an intent.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun KeysScreen(
    route: ConnectRoute.Keys,
    state: KeysState,
    onIntent: (KeysIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolver = LocalContext.current.contentResolver
    val source = remember(resolver) { ContentResolverKeyFileSource(resolver) }
    KeysScreen(route, state, onIntent, source, modifier)
}

/** [KeysScreen] reading picked files from [fileSource]: the seam tests use in place of a provider. */
@Composable
internal fun KeysScreen(
    route: ConnectRoute.Keys,
    state: KeysState,
    onIntent: (KeysIntent) -> Unit,
    fileSource: KeyFileSource,
    modifier: Modifier = Modifier,
    newBuffer: (Int) -> ByteArray = ::ByteArray,
) {
    val send by rememberUpdatedState(onIntent)
    val scope = rememberCoroutineScope()
    val reader = remember(fileSource, newBuffer) { KeyFileReader(fileSource, newBuffer = newBuffer) }
    val fallbackName = stringResource(R.string.connect_keys_import_default_name)
    var fileError by remember { mutableStateOf<KeyFileError?>(null) }
    var generating by remember { mutableStateOf(false) }
    // Not state: nothing draws it. Touched on the main thread only, by the picker's callback.
    val importJob = remember { ImportJobSlot() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // One read at a time: only the latest pick may send or report. A cancelled read sends
            // nothing, sets no error and wipes its bytes.
            importJob.value?.cancel()
            fileError = null
            importJob.value = scope.launch { fileError = reader.import(uri, fallbackName) { send(it) } }
        }
    }

    PhopencodeScaffold(
        title = stringResource(R.string.connect_keys_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(KeysIntent.Close) },
        actions = {
            TextButton(onClick = { picker.launch(KEY_FILE_TYPES) }, enabled = !state.busy && !state.loading) {
                Text(stringResource(R.string.connect_keys_import))
            }
            TextButton(onClick = { generating = true }, enabled = !state.busy && !state.loading) {
                Text(stringResource(R.string.connect_keys_generate))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) {
                val busy = stringResource(R.string.connect_keys_busy)
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = busy })
            }
            // A wrong passphrase is shown in its prompt instead.
            val keysError = state.error?.takeUnless { it == KeysError.WrongPassphrase && state.importAwaitingPassphrase }
            val spacing = PhopencodeTheme.spacing
            val shownFileError = fileError
            when {
                shownFileError != null -> ErrorBanner(
                    modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
                    message = stringResource(shownFileError.messageRes()),
                    onDismiss = { fileError = null },
                )
                keysError != null -> ErrorBanner(
                    modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
                    message = stringResource(keysError.messageRes()),
                    onDismiss = { onIntent(KeysIntent.DismissError) },
                )
            }
            when {
                state.loading -> Loading(Modifier.fillMaxSize(), stringResource(R.string.connect_keys_loading))
                state.keys.isEmpty() -> EmptyState(
                    modifier = Modifier.fillMaxSize(),
                    title = stringResource(R.string.connect_keys_empty_title),
                    message = stringResource(R.string.connect_keys_empty_message),
                )
                else -> KeyList(state.keys, enabled = !state.busy, onIntent = onIntent)
            }
        }
    }

    if (generating) {
        GenerateDialog(
            onGenerate = { name, passphrase ->
                generating = false
                onIntent(KeysIntent.Generate(name, passphrase))
            },
            onDismiss = { generating = false },
        )
    }
    if (state.importAwaitingPassphrase) {
        PassphraseDialog(
            wrongPassphrase = state.error == KeysError.WrongPassphrase,
            enabled = !state.busy,
            onSubmit = { onIntent(KeysIntent.SubmitPassphrase(it)) },
            onCancel = { onIntent(KeysIntent.CancelImport) },
        )
    }
    state.pendingDelete?.let { key ->
        ConfirmDialog(
            title = stringResource(R.string.connect_keys_delete_title),
            message = stringResource(R.string.connect_keys_delete_message, key.name),
            onConfirm = { onIntent(KeysIntent.ConfirmDelete) },
            onDismiss = { onIntent(KeysIntent.CancelDelete) },
            confirmLabel = stringResource(R.string.connect_keys_delete),
            destructive = true,
        )
    }
}

@Composable
private fun KeyList(keys: List<SshKeyEntry>, enabled: Boolean, onIntent: (KeysIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        items(keys, key = { it.id.value }) { key -> KeyCard(key, enabled, onIntent) }
    }
}

@Composable
private fun KeyCard(key: SshKeyEntry, enabled: Boolean, onIntent: (KeysIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val copyDescription = stringResource(R.string.connect_keys_copy_description, key.name)
    val deleteDescription = stringResource(R.string.connect_keys_delete_description, key.name)
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = key.name,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.connect_keys_type, key.type),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.connect_keys_fingerprint, key.fingerprint),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
            ) {
                TextButton(
                    onClick = { onIntent(KeysIntent.CopyPublicKey(key.id)) },
                    modifier = Modifier.semantics { contentDescription = copyDescription },
                ) {
                    Text(stringResource(R.string.connect_keys_copy))
                }
                TextButton(
                    onClick = { onIntent(KeysIntent.RequestDelete(key.id)) },
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = deleteDescription },
                ) {
                    Text(stringResource(R.string.connect_keys_delete))
                }
            }
        }
    }
}

/** A masked password field. The text lives in plain `remember`, never in saved state. */
@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
    )
}

@Composable
private fun GenerateDialog(onGenerate: (name: String, passphrase: String?) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.connect_keys_generate_title), Modifier.semantics { heading() })
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm)) {
                Text(stringResource(R.string.connect_keys_generate_message))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().testTag(GENERATE_NAME_TAG),
                    label = { Text(stringResource(R.string.connect_keys_generate_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                SecretField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = stringResource(R.string.connect_keys_generate_passphrase),
                    modifier = Modifier.testTag(GENERATE_PASSPHRASE_TAG),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val chosen = passphrase.ifEmpty { null }
                    passphrase = ""
                    onGenerate(trimmed, chosen)
                },
                enabled = trimmed.isNotEmpty(),
            ) {
                Text(stringResource(R.string.connect_keys_generate_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreUiR.string.ui_common_cancel))
            }
        },
    )
}

@Composable
private fun PassphraseDialog(
    wrongPassphrase: Boolean,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(stringResource(R.string.connect_keys_passphrase_title), Modifier.semantics { heading() })
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PhopencodeTheme.spacing.sm)) {
                Text(stringResource(R.string.connect_keys_passphrase_message))
                SecretField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = stringResource(R.string.connect_keys_passphrase_label),
                    modifier = Modifier.testTag(PASSPHRASE_FIELD_TAG),
                    isError = wrongPassphrase,
                )
                if (wrongPassphrase) {
                    Text(
                        text = stringResource(R.string.connect_keys_error_wrong_passphrase),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val typed = passphrase
                    passphrase = ""
                    onSubmit(typed)
                },
                enabled = enabled && passphrase.isNotEmpty(),
            ) {
                Text(stringResource(R.string.connect_keys_passphrase_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(CoreUiR.string.ui_common_cancel))
            }
        },
    )
}

private fun KeysError.messageRes(): Int = when (this) {
    KeysError.LoadFailed -> R.string.connect_keys_error_load_failed
    KeysError.GenerateFailed -> R.string.connect_keys_error_generate_failed
    KeysError.ImportMalformed -> R.string.connect_keys_error_import_malformed
    KeysError.ImportUnsupported -> R.string.connect_keys_error_import_unsupported
    KeysError.WrongPassphrase -> R.string.connect_keys_error_wrong_passphrase
    KeysError.KeyInUse -> R.string.connect_keys_error_key_in_use
    KeysError.SaveFailed -> R.string.connect_keys_error_save_failed
    KeysError.DeleteFailed -> R.string.connect_keys_error_delete_failed
}

private fun KeyFileError.messageRes(): Int = when (this) {
    KeyFileError.TooLarge -> R.string.connect_keys_error_file_too_large
    KeyFileError.Empty -> R.string.connect_keys_error_file_empty
    KeyFileError.Unreadable -> R.string.connect_keys_error_file_unreadable
}
