package io.github.thewisekd.phopencode.core.codeview

import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val WAIT_MS = 10_000L
private const val LONG_LINES = 300
private val LONG_TEXT = (1..LONG_LINES).joinToString("\n") { "line $it" }

/** The wrapper on a real editor surface: drawing, the IME connection and the clipboard. */
@RunWith(AndroidJUnit4::class)
class CodeViewInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private var editor: CodeEditor? = null

    private fun editor(): CodeEditor = checkNotNull(editor) { "the editor was not created" }

    private fun show(darkTheme: () -> Boolean = { false }, content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = if (darkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface { Box(Modifier.size(360.dp, 480.dp)) { content() } }
            }
        }
    }

    @Test
    fun readOnlyShowsTheTextAndBlocksTyping() {
        show { CodeViewBody(text = "val x = 1", language = CodeLanguage.Kotlin, readOnly = true, onEditor = { editor = it }) }
        compose.onNodeWithTag(CODE_VIEW_TAG).assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(editor().isEditable)
            assertEquals(null, editor().onCreateInputConnection(EditorInfo()))
            assertEquals("val x = 1", editor().text.toString())
        }
    }

    @Test
    fun readOnlyStillSelectsAndCopies() {
        show { CodeViewBody(text = "copy me", language = CodeLanguage.PlainText, readOnly = true, onEditor = { editor = it }) }
        compose.runOnIdle {
            editor().selectAll()
            assertTrue(editor().isTextSelected)
            editor().copyText()
        }
        compose.runOnIdle {
            val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
            assertEquals("copy me", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    @Test
    fun editModeTypesThroughTheInputConnectionAndReportsTheText() {
        val changes = mutableListOf<String>()
        show {
            var text by remember { mutableStateOf("ab") }
            CodeViewBody(
                text = text,
                language = CodeLanguage.Kotlin,
                readOnly = false,
                onTextChange = {
                    changes += it
                    text = it
                },
                onEditor = { editor = it },
            )
        }
        compose.runOnIdle {
            assertTrue(editor().isEditable)
            editor().setSelection(0, 1)
            val connection = editor().onCreateInputConnection(EditorInfo())
            assertNotNull(connection)
            connection.commitText("X", 1)
        }
        compose.runOnIdle {
            assertEquals("aXb", editor().text.toString())
            assertEquals("aXb", changes.last())
        }
    }

    @Test
    fun highlightingLoadsForAKnownLanguage() {
        show { CodeViewBody(text = "fun main() {}", language = CodeLanguage.Kotlin, readOnly = true, onEditor = { editor = it }) }
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { editor().editorLanguage is TextMateLanguage } }
    }

    @Test
    fun colorsFollowTheAppTheme() {
        var dark by mutableStateOf(true)
        show(darkTheme = { dark }) {
            CodeViewBody(text = "x", language = CodeLanguage.Kotlin, readOnly = true, onEditor = { editor = it })
        }
        compose.runOnIdle { assertTrue((editor().colorScheme as TextMateColorScheme).isDark) }
        dark = false
        compose.runOnIdle { assertFalse((editor().colorScheme as TextMateColorScheme).isDark) }
    }

    /** Waits until the editor has applied a line; the code view reports it on its node. */
    private fun awaitOpenedLine(line: Int) {
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithTag(CODE_VIEW_TAG).fetchSemanticsNodes()
                .any { it.config.getOrElseNullable(CodeViewOpenedLine) { null } == line }
        }
    }

    /** Whether the 0-based [line] lies fully inside the editor's visible height. */
    private fun lineIsVisible(line: Int): Boolean {
        val bottom = editor().getCharOffsetY(line, 0)
        val top = bottom - editor().rowHeight
        return top >= 0f && bottom <= editor().height.toFloat()
    }

    @Test
    fun aLineFarDownScrollsIntoViewWithTheCursorAtItsStart() {
        show { CodeViewBody(text = LONG_TEXT, language = CodeLanguage.Kotlin, line = 250, onEditor = { editor = it }) }
        awaitOpenedLine(250)
        compose.runOnIdle {
            assertTrue("the editor did not scroll", editor().offsetY > 0)
            assertTrue("line 250 is not on screen", lineIsVisible(249))
            assertEquals(249, editor().cursor.leftLine)
            assertEquals(0, editor().cursor.leftColumn)
            assertEquals(null, editor().onCreateInputConnection(EditorInfo()))
        }
    }

    @Test
    fun aLinePastTheEndShowsTheLastLineAndAMissingLineTheTop() {
        var line by mutableStateOf<Int?>(Int.MAX_VALUE)
        show { CodeViewBody(text = LONG_TEXT, language = CodeLanguage.PlainText, line = line, onEditor = { editor = it }) }
        awaitOpenedLine(LONG_LINES)
        compose.runOnIdle { assertTrue("the last line is not on screen", lineIsVisible(LONG_LINES - 1)) }
        line = null
        awaitOpenedLine(1)
        compose.runOnIdle { assertEquals(0, editor().offsetY) }
    }

    @Test
    fun theUsersOwnScrollingIsKeptOnRecomposition() {
        var dark by mutableStateOf(false)
        show(darkTheme = { dark }) {
            CodeViewBody(text = LONG_TEXT, language = CodeLanguage.PlainText, line = 250, onEditor = { editor = it })
        }
        awaitOpenedLine(250)
        compose.runOnIdle {
            val scroller = editor().scroller
            scroller.forceFinished(true)
            scroller.startScroll(0, editor().offsetY, 0, -editor().offsetY, 0)
            scroller.abortAnimation()
        }
        dark = true
        compose.runOnIdle { assertEquals(0, editor().offsetY) }
    }

    @Test
    fun leavingTheCompositionReleasesTheEditor() {
        var shown by mutableStateOf(true)
        show { if (shown) CodeViewBody(text = "x", language = CodeLanguage.Kotlin, readOnly = true, onEditor = { editor = it }) }
        compose.runOnIdle { assertFalse(editor().isReleased) }
        shown = false
        compose.runOnIdle { assertTrue(editor().isReleased) }
    }
}
