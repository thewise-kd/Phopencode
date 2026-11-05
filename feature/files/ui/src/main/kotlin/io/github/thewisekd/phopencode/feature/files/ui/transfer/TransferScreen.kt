package io.github.thewisekd.phopencode.feature.files.ui.transfer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferDirection
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferId
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferJob
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferState
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferStatus
import io.github.thewisekd.phopencode.feature.files.ui.R
import io.github.thewisekd.phopencode.feature.files.ui.viewer.fileName

/** Tags on the transfer screen's parts, for tests. */
internal object TransferTags {
    const val LIST = "files:transfer:list"
    const val FOLDER = "files:transfer:folder"
    const val UPLOAD = "files:transfer:upload"
    const val CLEAR = "files:transfer:clear"
    const val EMPTY = "files:transfer:empty"
    const val PICKER_MISSING = "files:transfer:picker-missing"
    const val OVERWRITE = "files:transfer:overwrite"
    const val OVERWRITE_REPLACE = "files:transfer:overwrite:replace"
    const val OVERWRITE_SKIP = "files:transfer:overwrite:skip"

    fun job(id: TransferId) = "files:transfer:job:${id.value}"

    fun status(id: TransferId) = "files:transfer:status:${id.value}"

    fun progress(id: TransferId) = "files:transfer:progress:${id.value}"

    fun cancel(id: TransferId) = "files:transfer:cancel:${id.value}"
}

/**
 * Uploads into the route's folder and downloads from it, through the system's pickers only: the
 * app asks for no storage permission. Draws [state] and sends [onIntent]; the transfers run in the
 * app's queue and go on after the screen closes.
 *
 * - Upload opens the system picker for any number of files; each picked document is queued under
 *   its own name. A [TransferState.pendingDownload] opens the system's create-document picker named
 *   after the file, once per request; closing it without a place cancels the download.
 * - Every job of the queue shows with its name and status. A running job of known size has a
 *   determinate progress bar; of unknown size it has none, never an endless one. Unfinished jobs
 *   can be cancelled, finished ones cleared.
 * - The oldest upload waiting for an overwrite answer asks in a dialog, never a bottom sheet:
 *   replace, or skip, which back also means.
 * - While any job is unfinished the screen starts [TransferService], which shows the progress in a
 *   notification and stops itself when the queue is idle. On API 33 and later it first asks once
 *   for the notification permission; the transfers run whatever the answer.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun TransferScreen(
    route: FilesRoute.Transfer,
    state: TransferState,
    onIntent: (TransferIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val documents = remember(context) { ContentDocuments(context.contentResolver) }
    TransferScreen(
        route = route,
        state = state,
        onIntent = onIntent,
        documents = documents,
        startService = { TransferService.start(context.applicationContext) },
        modifier = modifier,
    )
}

/** [TransferScreen] with the documents' access and the service's start given, for tests. */
@Composable
internal fun TransferScreen(
    route: FilesRoute.Transfer,
    state: TransferState,
    onIntent: (TransferIntent) -> Unit,
    documents: DocumentAccess,
    startService: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val send by rememberUpdatedState(onIntent)
    val start by rememberUpdatedState(startService)
    val access by rememberUpdatedState(documents)
    val fallbackName = stringResource(R.string.files_transfer_default_name)
    var pickerMissing by rememberSaveable { mutableStateOf(false) }
    /** The pending download whose picker was opened, so that it opens once, rotation included. */
    var pickedFor by rememberSaveable { mutableStateOf<String?>(null) }

    /*
     * A picker's result comes once, so the picked documents go out in its callback, before anything
     * is read: their names and sizes are read when the queue first asks. Closing or rotating the
     * screen after a pick then loses nothing.
     */
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        send(TransferIntent.Upload(uris.map { access.open(it, fallbackName, forWriting = false) }))
        start()
    }
    val downloadPicker = rememberLauncherForActivityResult(CreateDocumentContract()) { uri ->
        if (uri == null) {
            send(TransferIntent.CancelDownload)
        } else {
            val name = pickedFor?.let(::fileName) ?: fallbackName
            send(TransferIntent.DownloadTo(access.open(uri, name, forWriting = true)))
            start()
        }
    }

    val pending = state.pendingDownload
    LaunchedEffect(pending) {
        if (pending == null) {
            pickedFor = null
        } else if (pickedFor != pending) {
            pickedFor = pending
            try {
                downloadPicker.launch(downloadTarget(fileName(pending)))
            } catch (missing: ActivityNotFoundException) {
                pickerMissing = true
                send(TransferIntent.CancelDownload)
            }
        }
    }

    val active = state.jobs.any { !it.status.isFinished }
    LaunchedEffect(active) {
        if (active) start()
    }
    AskForNotifications(active = active && pending == null)

    PhopencodeScaffold(
        title = stringResource(R.string.files_transfer_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { send(TransferIntent.Close) },
    ) { padding ->
        val spacing = PhopencodeTheme.spacing
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag(TransferTags.LIST),
            contentPadding = PaddingValues(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item(key = "header") {
                Header(
                    folder = state.folder.ifEmpty { state.directory },
                    canClear = state.hasFinished,
                    onUpload = {
                        try {
                            uploadPicker.launch(arrayOf("*/*"))
                        } catch (missing: ActivityNotFoundException) {
                            pickerMissing = true
                        }
                    },
                    onClear = { send(TransferIntent.ClearFinished) },
                )
            }
            if (pickerMissing) {
                item(key = "picker-missing") {
                    ErrorBanner(
                        modifier = Modifier.testTag(TransferTags.PICKER_MISSING),
                        message = stringResource(R.string.files_transfer_picker_missing),
                        onDismiss = { pickerMissing = false },
                    )
                }
            }
            if (state.jobs.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        modifier = Modifier.fillMaxWidth().testTag(TransferTags.EMPTY),
                        title = stringResource(R.string.files_transfer_empty_title),
                        message = stringResource(R.string.files_transfer_empty_message),
                    )
                }
            }
            items(state.jobs, key = { it.id.value }) { job ->
                JobRow(job, onCancel = { send(TransferIntent.Cancel(job.id)) })
            }
        }
    }
    state.overwritePrompt?.let { job -> OverwriteDialog(job, send) }
}

@Composable
private fun Header(folder: String, canClear: Boolean, onUpload: () -> Unit, onClear: () -> Unit) {
    val spacing = PhopencodeTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.files_transfer_folder, fileName(folder)),
            modifier = Modifier.testTag(TransferTags.FOLDER),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onUpload, modifier = Modifier.testTag(TransferTags.UPLOAD)) {
                Text(stringResource(R.string.files_transfer_upload))
            }
            TextButton(onClick = onClear, enabled = canClear, modifier = Modifier.testTag(TransferTags.CLEAR)) {
                Text(stringResource(R.string.files_transfer_clear_finished))
            }
        }
    }
}

@Composable
private fun JobRow(job: TransferJob, onCancel: () -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val failed = job.status is TransferStatus.Failed
    Column(
        modifier = Modifier.fillMaxWidth().testTag(TransferTags.job(job.id)),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = baseName(job.localName),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = statusText(job),
                    modifier = Modifier.testTag(TransferTags.status(job.id)),
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!job.status.isFinished) {
                TextButton(onClick = onCancel, modifier = Modifier.testTag(TransferTags.cancel(job.id))) {
                    Text(stringResource(R.string.files_transfer_cancel))
                }
            }
        }
        val fraction = job.fraction
        if (job.status == TransferStatus.Running && fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().testTag(TransferTags.progress(job.id)),
            )
        }
    }
}

@Composable
private fun statusText(job: TransferJob): String {
    val upload = job.direction == TransferDirection.Upload
    return when (val status = job.status) {
        TransferStatus.Queued -> stringResource(R.string.files_transfer_status_queued)
        TransferStatus.Running -> {
            val percent = job.fraction?.let { (it * 100).toInt() }
            when {
                percent == null && upload -> stringResource(R.string.files_transfer_status_uploading)
                percent == null -> stringResource(R.string.files_transfer_status_downloading)
                upload -> stringResource(R.string.files_transfer_status_uploading_percent, percent)
                else -> stringResource(R.string.files_transfer_status_downloading_percent, percent)
            }
        }
        TransferStatus.AwaitingOverwrite -> stringResource(R.string.files_transfer_status_awaiting)
        TransferStatus.Done ->
            stringResource(if (upload) R.string.files_transfer_status_uploaded else R.string.files_transfer_status_downloaded)
        TransferStatus.Cancelled -> stringResource(R.string.files_transfer_status_cancelled)
        is TransferStatus.Failed ->
            stringResource(R.string.files_transfer_status_failed, stringResource(status.reason.reasonRes()))
    }
}

/**
 * Replace the file of the same name, or skip the upload. Back means skip, the side that changes
 * nothing; a tap outside does nothing, so a stray touch never answers.
 */
@Composable
private fun OverwriteDialog(job: TransferJob, onIntent: (TransferIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val name = baseName(job.localName)
    Dialog(
        onDismissRequest = { onIntent(TransferIntent.AnswerOverwrite(job.id, overwrite = false)) },
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier.testTag(TransferTags.OVERWRITE),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = spacing.xs,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Text(
                    text = stringResource(R.string.files_transfer_overwrite_title, name),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.files_transfer_overwrite_message, name),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(modifier = Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    TextButton(
                        onClick = { onIntent(TransferIntent.AnswerOverwrite(job.id, overwrite = false)) },
                        modifier = Modifier.testTag(TransferTags.OVERWRITE_SKIP),
                    ) {
                        Text(stringResource(R.string.files_transfer_overwrite_skip))
                    }
                    TextButton(
                        onClick = { onIntent(TransferIntent.AnswerOverwrite(job.id, overwrite = true)) },
                        modifier = Modifier.testTag(TransferTags.OVERWRITE_REPLACE),
                    ) {
                        Text(stringResource(R.string.files_transfer_overwrite_replace))
                    }
                }
            }
        }
    }
}

/**
 * On API 33 and later, asks once per screen for the permission to post the progress notification,
 * when a transfer is [active] and the permission is missing. A refusal changes nothing else.
 */
@Composable
private fun AskForNotifications(active: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _: Boolean? -> }
    LaunchedEffect(active) {
        if (active && !asked && !canNotify(context)) {
            asked = true
            try {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (missing: ActivityNotFoundException) {
                // No one to ask: the transfers run without the notification.
            }
        }
    }
}

private fun canNotify(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** Why the transfer failed, as the end of a sentence that says it did. */
@StringRes
private fun FilesFailure.reasonRes(): Int = when (this) {
    FilesFailure.NotConnected -> R.string.files_transfer_reason_not_connected
    FilesFailure.Unreachable -> R.string.files_transfer_reason_unreachable
    FilesFailure.Unauthorized -> R.string.files_transfer_reason_unauthorized
    FilesFailure.NotFound -> R.string.files_transfer_reason_not_found
    FilesFailure.PermissionDenied -> R.string.files_transfer_reason_permission_denied
    FilesFailure.AlreadyExists -> R.string.files_transfer_reason_already_exists
    FilesFailure.NotEmpty -> R.string.files_transfer_reason_not_empty
    FilesFailure.PathRejected -> R.string.files_transfer_reason_path_rejected
    FilesFailure.TooLarge -> R.string.files_transfer_reason_too_large
    FilesFailure.ToolMissing -> R.string.files_transfer_reason_tool_missing
    FilesFailure.Rejected -> R.string.files_transfer_reason_rejected
    FilesFailure.ServerFailed -> R.string.files_transfer_reason_server_failed
    FilesFailure.Unsupported -> R.string.files_transfer_reason_unsupported
    FilesFailure.Unknown -> R.string.files_transfer_reason_unknown
}
