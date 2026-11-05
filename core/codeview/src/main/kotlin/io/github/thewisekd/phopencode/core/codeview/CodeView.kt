package io.github.thewisekd.phopencode.core.codeview

import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The tag on the editor's node, for tests. */
internal const val CODE_VIEW_TAG = "codeview"

private const val HALF_LUMINANCE = 0.5f
private const val TEXT_SIZE_SP = 14f

/**
 * Shows [text] in a code editor with line numbers and syntax highlighting.
 *
 * - **Language:** [language] picks the TextMate grammar; get it from a file name with
 *   [CodeLanguage.fromFileName]. [CodeLanguage.PlainText] shows the text without colors.
 *   A grammar is read from the assets the first time any editor needs it, off the main
 *   thread, and kept for the life of the process; until it is ready the text shows plain.
 *   When [language] changes, the old highlighting goes at once and the text shows plain
 *   until the new grammar is ready.
 * - **Read-only (default):** the text cannot change, but it can still be selected and
 *   copied, and the keyboard does not open.
 * - **Edit mode** (`readOnly = false`): every change made in the editor calls
 *   [onTextChange] with the whole new text. Pass that text back as [text] (state
 *   hoisting); a [text] that differs from what the editor holds replaces the content and
 *   does not call [onTextChange], so a caller that does not pass the change back loses
 *   it on the next recomposition. Line endings are kept as they are. Each change copies
 *   the whole text, which suits files within the viewer's size limit.
 * - **Theme:** dark or light colors follow `MaterialTheme.colorScheme`, by the luminance
 *   of its surface color. The theme is shared by every CodeView in the process, so all of
 *   them switch together; the app has one theme at a time, so they never disagree.
 *
 * The editor scrolls by itself in both directions and does not wrap lines: give it a
 * bounded size, for example `Modifier.fillMaxSize()`, and do not put it in a vertical
 * scroll container. The editor is released when this leaves the composition. The text is
 * user data and never logged.
 */
@Composable
fun CodeView(
    text: String,
    language: CodeLanguage,
    modifier: Modifier = Modifier,
    readOnly: Boolean = true,
    onTextChange: (String) -> Unit = {},
) {
    CodeViewBody(text = text, language = language, modifier = modifier, readOnly = readOnly, onTextChange = onTextChange)
}

/** What the editor last showed or reported, so updates neither loop nor repeat. */
private class EditorState {
    var text: String? = null
    var dark: Boolean? = null
    var language: CodeLanguage? = null
    var highlighted = false
}

/** Reads a grammar on a background thread; the result is kept in the loader. */
private val LoadGrammarOffMainThread: suspend (TextMateLoader, CodeLanguage) -> Unit = { loader, language ->
    withContext(Dispatchers.Default) { loader.loadGrammar(language) }
}

/** [CodeView] with a hook that hands tests the editor once it exists. */
@Composable
internal fun CodeViewBody(
    text: String,
    language: CodeLanguage,
    modifier: Modifier = Modifier,
    readOnly: Boolean = true,
    onTextChange: (String) -> Unit = {},
    onEditor: (CodeEditor) -> Unit = {},
    loadGrammar: suspend (TextMateLoader, CodeLanguage) -> Unit = LoadGrammarOffMainThread,
) {
    val context = LocalContext.current
    val loader = remember(context) { TextMateLoader.shared(context) }
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < HALF_LUMINANCE
    val currentOnTextChange by rememberUpdatedState(onTextChange)
    val state = remember { EditorState() }

    // The language whose grammar is ready, or null while it loads. A change of language
    // cancels the load in flight, and the editor also checks the result against [language].
    val readyLanguage by produceState<CodeLanguage?>(null, language, loader) {
        value = null
        loadGrammar(loader, language)
        value = language
    }

    AndroidView(
        factory = { viewContext ->
            CodeEditor(viewContext).apply {
                setTypefaceText(Typeface.MONOSPACE)
                setTextSize(TEXT_SIZE_SP)
                isWordwrap = false
                setLineNumberEnabled(true)
                // The cursor neither blinks nor glides: no endless animation, no idle-wait in tests.
                setCursorBlinkPeriod(0)
                isCursorAnimationEnabled = false
                // The theme file is small; reading it here once keeps the first frame in the right colors.
                if (loader.loadTheme(darkTheme)) {
                    loader.applyTheme(darkTheme)
                    colorScheme = loader.colorScheme()
                }
                state.dark = darkTheme
                state.text = text
                setText(text)
                subscribeAlways(ContentChangeEvent::class.java) {
                    val now = this.text.toString()
                    if (now != state.text) {
                        state.text = now
                        currentOnTextChange(now)
                    }
                }
                onEditor(this)
            }
        },
        update = { editor ->
            editor.isEditable = !readOnly
            if (text != state.text) {
                state.text = text
                editor.setText(text)
            }
            if (state.dark != darkTheme && loader.loadTheme(darkTheme)) {
                loader.applyTheme(darkTheme)
                if (editor.colorScheme !is TextMateColorScheme) {
                    editor.colorScheme = loader.colorScheme()
                }
                state.dark = darkTheme
            }
            // A new language drops the old highlighter at once; plain text shows until its grammar loads.
            if (state.language != language) {
                editor.setEditorLanguage(EmptyLanguage())
                state.language = language
                state.highlighted = false
            }
            // Only a load for the current language counts; one that finished for an earlier language is stale.
            if (readyLanguage == language && !state.highlighted) {
                loader.createLanguage(language)?.let(editor::setEditorLanguage)
                state.highlighted = true
            }
        },
        onRelease = { editor -> editor.release() },
        modifier = modifier.testTag(CODE_VIEW_TAG),
    )
}
