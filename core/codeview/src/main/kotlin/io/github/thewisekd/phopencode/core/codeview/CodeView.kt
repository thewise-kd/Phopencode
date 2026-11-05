package io.github.thewisekd.phopencode.core.codeview

import android.graphics.Typeface
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The 1-based line the code view last opened at, on the code view's node: the requested line
 * after clamping, or 1 when it opened at the top. It is set once the editor has been laid out
 * and has applied the request, so tests can check where a line went.
 */
val CodeViewOpenedLine = SemanticsPropertyKey<Int>("CodeViewOpenedLine")

/** The tag on the editor's node, for tests. */
internal const val CODE_VIEW_TAG = "codeview"

private const val HALF_LUMINANCE = 0.5f
private const val TEXT_SIZE_SP = 14f

/** Rows left visible above a requested line, so it does not sit on the top edge. */
private const val CONTEXT_ROWS_ABOVE = 2

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
 * - **Line:** [line] opens the text at that 1-based line: the cursor goes to the start of the
 *   line, nothing is selected, the keyboard stays closed in read-only mode, and the line is
 *   scrolled into view near the top, without animation. Null, zero or a negative line opens
 *   at the top; a line past the end opens at the last line. The line is applied once when
 *   the editor is first laid out, and again only when [line] changes or a new [text] comes
 *   from outside, so recomposition never pulls the user back from where they scrolled, and
 *   text the editor reports through [onTextChange] does not count as new. The line it opened
 *   at is reported on the node as [CodeViewOpenedLine].
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
    line: Int? = null,
) {
    CodeViewBody(text = text, language = language, modifier = modifier, readOnly = readOnly, onTextChange = onTextChange, line = line)
}

/** What the editor last showed or reported, so updates neither loop nor repeat. */
private class EditorState {
    var text: String? = null
    var dark: Boolean? = null
    var language: CodeLanguage? = null
    var highlighted = false

    /** The requested line, and whether it still has to be applied. */
    var line: Int? = null
    var linePending = true

    /** Whether a layout listener already waits to apply [line]. */
    var waitingForLayout = false
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
    line: Int? = null,
    onEditor: (CodeEditor) -> Unit = {},
    loadGrammar: suspend (TextMateLoader, CodeLanguage) -> Unit = LoadGrammarOffMainThread,
) {
    val context = LocalContext.current
    val loader = remember(context) { TextMateLoader.shared(context) }
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < HALF_LUMINANCE
    val currentOnTextChange by rememberUpdatedState(onTextChange)
    val state = remember { EditorState() }
    var openedLine by remember { mutableStateOf<Int?>(null) }
    val onOpened: (Int) -> Unit = { openedLine = it }

    // The language whose grammar is ready, or null while it loads. A change of language
    // cancels the load in flight, and the editor also checks the result against [language].
    val readyLanguage by produceState<CodeLanguage?>(null, language, loader) {
        value = null
        loadGrammar(loader, language)
        value = language
    }

    val opened = openedLine
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
                state.line = line
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
                state.linePending = true
            }
            if (line != state.line) {
                state.line = line
                state.linePending = true
            }
            if (state.linePending) {
                openWhenLaidOut(editor, state, onOpened)
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
        modifier = modifier.testTag(CODE_VIEW_TAG).semantics {
            if (opened != null) this[CodeViewOpenedLine] = opened
        },
    )
}

/**
 * Applies the pending line now if the editor has a size, or else after its first layout with a
 * size: before that, the editor cannot know how far to scroll.
 */
private fun openWhenLaidOut(editor: CodeEditor, state: EditorState, onOpened: (Int) -> Unit) {
    if (editor.isLaidOut && editor.width > 0 && editor.height > 0) {
        state.linePending = false
        onOpened(editor.openAt(state.line))
        return
    }
    if (state.waitingForLayout) return
    state.waitingForLayout = true
    editor.addOnLayoutChangeListener(
        object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                if (right - left <= 0 || bottom - top <= 0) return
                editor.removeOnLayoutChangeListener(this)
                state.waitingForLayout = false
                if (state.linePending && !editor.isReleased) {
                    state.linePending = false
                    onOpened(editor.openAt(state.line))
                }
            }
        },
    )
}

/**
 * Puts the cursor at the start of the 1-based [line] and scrolls so that the line shows near the
 * top, at once and without animation. Null, zero or a negative line means the top; a line past
 * the end means the last line. Returns the 1-based line it opened at.
 */
private fun CodeEditor.openAt(line: Int?): Int {
    val lastIndex = (lineCount - 1).coerceAtLeast(0)
    val index = if (line == null || line <= 0) 0 else (line - 1).coerceAtMost(lastIndex)
    setSelection(index, 0, false)
    // The layout gives the bottom edge of the line's row; leave a few rows above it for context.
    val rowBottom = if (index == 0) 0f else layout?.getCharLayoutOffset(index, 0)?.get(0) ?: 0f
    val targetY = (rowBottom - rowHeight * (CONTEXT_ROWS_ABOVE + 1)).toInt()
        .coerceIn(0, scrollMaxY.coerceAtLeast(0))
    val scroller = scroller
    scroller.forceFinished(true)
    scroller.startScroll(offsetX, offsetY, -offsetX, targetY - offsetY, 0)
    scroller.abortAnimation()
    invalidate()
    return index + 1
}
