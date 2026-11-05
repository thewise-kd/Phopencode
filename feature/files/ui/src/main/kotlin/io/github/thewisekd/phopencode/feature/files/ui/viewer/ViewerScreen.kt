package io.github.thewisekd.phopencode.feature.files.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import io.github.thewisekd.phopencode.core.codeview.CodeLanguage
import io.github.thewisekd.phopencode.core.codeview.CodeView
import io.github.thewisekd.phopencode.core.markdown.MarkdownView
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerMode
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerState
import io.github.thewisekd.phopencode.feature.files.ui.R

/** Tags on the viewer's parts, for tests. */
internal object ViewerTags {
    const val LOADING = "files:viewer:loading"
    const val ERROR = "files:viewer:error"
    const val CODE = "files:viewer:code"
    const val MARKDOWN = "files:viewer:markdown"
    const val MODE_RENDERED = "files:viewer:mode:rendered"
    const val MODE_RAW = "files:viewer:mode:raw"
    const val MALFORMED = "files:viewer:malformed"
    const val IMAGE = "files:viewer:image"
    const val IMAGE_PICTURE = "files:viewer:image:picture"
    const val IMAGE_FAILED = "files:viewer:image:failed"
    const val BINARY = "files:viewer:binary"
    const val TOO_LARGE = "files:viewer:too-large"
    const val OPEN_ANYWAY = "files:viewer:open-anyway"
    const val EDIT = "files:viewer:edit"
}

/**
 * One file, read-only. Draws [state] and sends [onIntent]; it decides nothing.
 *
 * - The top bar names the file; its back button sends [ViewerIntent.Close], and an Edit button,
 *   shown only when [ViewerState.canEdit], sends [ViewerIntent.Edit].
 * - Text shows in the highlighted code view (P8.3), with the language from the path's extension.
 *   Markdown shows rendered (P6.2) or raw by [ViewerState.mode], with a switch that sends
 *   [ViewerIntent.SetMode]. Text with invalid UTF-8 shows a warning above it.
 * - An image shows as a preview fitted to the screen, decoded at a bounded size.
 * - A binary file, a file too large to read, and a failed read each say so; a large file below the
 *   hard limit offers [ViewerIntent.OpenAnyway], and a failed read [ViewerIntent.Retry].
 * - While loading, a line of text shows; while the file is read again, the loaded content stays.
 *
 * Nothing animates without end: there is no spinner, and the code view's cursor does not blink.
 *
 * Line: highlighted text and raw Markdown open at [ViewerState.line] (the route's line), with the
 * cursor at its start and the line scrolled into view; a missing, zero or negative line opens at the
 * top, and a line past the end at the last line. The line is applied when the text arrives and when
 * it changes, never again on a plain recomposition. Rendered Markdown has no lines to go to, so it
 * opens at the top.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun ViewerScreen(
    route: FilesRoute.Viewer,
    state: ViewerState,
    onIntent: (ViewerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = fileName(state.path),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(ViewerIntent.Close) },
        actions = {
            if (state.canEdit) {
                TextButton(onClick = { onIntent(ViewerIntent.Edit) }, modifier = Modifier.testTag(ViewerTags.EDIT)) {
                    Text(stringResource(R.string.files_viewer_edit))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Body(state, onIntent)
        }
    }
}

@Composable
private fun ColumnScope.Body(state: ViewerState, onIntent: (ViewerIntent) -> Unit) {
    val content = state.content
    val error = state.error
    when {
        content != null -> Content(state, content, onIntent)
        error != null -> Scrolling {
            ErrorBanner(
                modifier = Modifier.testTag(ViewerTags.ERROR),
                message = stringResource(R.string.files_viewer_error, stringResource(error.reasonRes())),
                onRetry = { onIntent(ViewerIntent.Retry) },
            )
        }
        else -> Text(
            text = stringResource(R.string.files_viewer_loading),
            modifier = Modifier
                .fillMaxWidth()
                .padding(PhopencodeTheme.spacing.xl)
                .testTag(ViewerTags.LOADING),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ColumnScope.Content(state: ViewerState, content: ViewerContent, onIntent: (ViewerIntent) -> Unit) {
    val fill = Modifier.weight(1f).fillMaxWidth()
    when (content) {
        is ViewerContent.Text -> {
            if (content.markdown) {
                ModeSwitch(state.mode, onIntent)
            }
            if (content.malformed) {
                MalformedWarning()
            }
            if (content.markdown && state.mode == ViewerMode.Rendered) {
                Column(fill.verticalScroll(rememberScrollState())) {
                    MarkdownView(
                        markdown = content.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(PhopencodeTheme.spacing.lg)
                            .testTag(ViewerTags.MARKDOWN),
                    )
                }
            } else {
                CodeView(
                    text = content.text,
                    language = CodeLanguage.fromFileName(state.path),
                    modifier = fill.testTag(ViewerTags.CODE),
                    line = state.line,
                )
            }
        }
        is ViewerContent.Image -> ImagePreview(content, fileName(state.path), fill)
        is ViewerContent.Binary -> Scrolling {
            EmptyState(
                modifier = Modifier.fillMaxWidth().testTag(ViewerTags.BINARY),
                title = stringResource(R.string.files_viewer_binary_title),
                message = stringResource(R.string.files_viewer_binary_message, formatSize(content.sizeBytes)),
            )
        }
        is ViewerContent.TooLarge -> Scrolling { TooLarge(content, onIntent) }
    }
}

@Composable
private fun TooLarge(content: ViewerContent.TooLarge, onIntent: (ViewerIntent) -> Unit) {
    val size = formatSize(content.sizeBytes)
    val limit = formatSize(content.limitBytes)
    if (content.canOpenAnyway) {
        Column {
            EmptyState(
                modifier = Modifier.fillMaxWidth().testTag(ViewerTags.TOO_LARGE),
                title = stringResource(R.string.files_viewer_too_large_title),
                message = stringResource(R.string.files_viewer_too_large_warning, size, limit),
            )
            OpenAnyway(onIntent)
        }
    } else {
        EmptyState(
            modifier = Modifier.fillMaxWidth().testTag(ViewerTags.TOO_LARGE),
            title = stringResource(R.string.files_viewer_too_large_title),
            message = stringResource(R.string.files_viewer_too_large_blocked, size, limit),
        )
    }
}

/** The button under a large file's warning. */
@Composable
private fun OpenAnyway(onIntent: (ViewerIntent) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Button(onClick = { onIntent(ViewerIntent.OpenAnyway) }, modifier = Modifier.testTag(ViewerTags.OPEN_ANYWAY)) {
            Text(stringResource(R.string.files_viewer_open_anyway))
        }
    }
}

/** Rendered or raw Markdown; tapping the mode already shown sends nothing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(mode: ViewerMode, onIntent: (ViewerIntent) -> Unit) {
    val options = listOf(
        Triple(ViewerMode.Rendered, R.string.files_viewer_mode_rendered, ViewerTags.MODE_RENDERED),
        Triple(ViewerMode.Raw, R.string.files_viewer_mode_raw, ViewerTags.MODE_RAW),
    )
    val spacing = PhopencodeTheme.spacing
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
    ) {
        options.forEachIndexed { index, (option, label, tag) ->
            SegmentedButton(
                selected = mode == option,
                onClick = { if (mode != option) onIntent(ViewerIntent.SetMode(option)) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.testTag(tag),
            ) {
                Text(stringResource(label))
            }
        }
    }
}

/** Says that some bytes were replaced, which is also why the file cannot be edited. */
@Composable
private fun MalformedWarning() {
    val spacing = PhopencodeTheme.spacing
    val colors = PhopencodeTheme.status.warning
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        color = colors.container,
        contentColor = colors.onContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = stringResource(R.string.files_viewer_malformed),
            modifier = Modifier
                .padding(horizontal = spacing.md, vertical = spacing.sm)
                .testTag(ViewerTags.MALFORMED),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** A message that scrolls if the screen is too short for it, as on a small phone in landscape. */
@Composable
private fun ColumnScope.Scrolling(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PhopencodeTheme.spacing.lg),
        content = content,
    )
}
