package io.github.thewisekd.phopencode.core.codeview

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val WAIT_MS = 5_000L
private const val LONG_LINES = 300
private val LONG_TEXT = (1..LONG_LINES).joinToString("") { "line $it\n" }.dropLast(1)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class CodeViewTest {
    @get:Rule
    val compose = createComposeRule()

    private var editor: CodeEditor? = null

    private fun editor(): CodeEditor = assertNotNull(editor, "the editor was not created")

    @Test
    fun `read-only shows the text and blocks editing`() {
        compose.setCodeViewContent {
            CodeViewBody(text = "val x = 1", language = CodeLanguage.Kotlin, readOnly = true, onEditor = { editor = it })
        }
        compose.onNodeWithTag(CODE_VIEW_TAG).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("val x = 1", editor().text.toString())
            assertFalse(editor().isEditable)
        }
    }

    @Test
    fun `read-only still selects and copies`() {
        compose.setCodeViewContent {
            CodeViewBody(text = "copy me", language = CodeLanguage.PlainText, readOnly = true, onEditor = { editor = it })
        }
        compose.runOnIdle {
            editor().selectAll()
            assertTrue(editor().isTextSelected)
            editor().copyText()
            val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
            assertEquals("copy me", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertEquals("copy me", editor().text.toString())
        }
    }

    @Test
    fun `edit mode reports each change`() {
        val changes = mutableListOf<String>()
        compose.setCodeViewContent {
            var text by androidx.compose.runtime.remember { mutableStateOf("ab") }
            CodeViewBody(
                text = text,
                language = CodeLanguage.PlainText,
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
            editor().text.insert(0, 1, "X")
        }
        compose.runOnIdle {
            assertEquals(listOf("aXb"), changes)
            assertEquals("aXb", editor().text.toString())
        }
    }

    @Test
    fun `new text from outside replaces the content without reporting a change`() {
        val changes = mutableListOf<String>()
        var text by mutableStateOf("first")
        compose.setCodeViewContent {
            CodeViewBody(text = text, language = CodeLanguage.PlainText, readOnly = false, onTextChange = { changes += it }, onEditor = { editor = it })
        }
        compose.runOnIdle { text = "second\r\nline" }
        compose.runOnIdle {
            assertEquals("second\r\nline", editor().text.toString())
            assertEquals(emptyList(), changes)
        }
    }

    @Test
    fun `the read-only flag can change while shown`() {
        var readOnly by mutableStateOf(true)
        compose.setCodeViewContent {
            CodeViewBody(text = "x", language = CodeLanguage.PlainText, readOnly = readOnly, onEditor = { editor = it })
        }
        compose.runOnIdle { assertFalse(editor().isEditable) }
        readOnly = false
        compose.runOnIdle { assertTrue(editor().isEditable) }
    }

    @Test
    fun `a known language gets a TextMate highlighter and plain text none`() {
        var language by mutableStateOf(CodeLanguage.Kotlin)
        compose.setCodeViewContent {
            CodeViewBody(text = "fun main() {}", language = language, readOnly = true, onEditor = { editor = it })
        }
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { editor().editorLanguage is TextMateLanguage } }
        language = CodeLanguage.PlainText
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { editor().editorLanguage !is TextMateLanguage } }
    }

    /** Holds each grammar load until the test releases it, then loads it for real. */
    private class GatedGrammars {
        private val gates = HashMap<CodeLanguage, CompletableDeferred<Unit>>()

        fun gate(language: CodeLanguage): CompletableDeferred<Unit> =
            synchronized(gates) { gates.getOrPut(language) { CompletableDeferred() } }

        val load: suspend (TextMateLoader, CodeLanguage) -> Unit = { loader, language ->
            gate(language).await()
            loader.loadGrammar(language)
        }
    }

    @Test
    fun `a language change shows plain text until the new grammar loads`() {
        val grammars = GatedGrammars()
        var language by mutableStateOf(CodeLanguage.Kotlin)
        compose.setCodeViewContent {
            CodeViewBody(
                text = "fn main() {}",
                language = language,
                readOnly = true,
                onEditor = { editor = it },
                loadGrammar = grammars.load,
            )
        }
        grammars.gate(CodeLanguage.Kotlin).complete(Unit)
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { editor().editorLanguage is TextMateLanguage } }

        language = CodeLanguage.Rust
        compose.runOnIdle { assertFalse(editor().editorLanguage is TextMateLanguage, "the Kotlin highlighter stayed") }

        grammars.gate(CodeLanguage.Rust).complete(Unit)
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { editor().editorLanguage is TextMateLanguage } }
    }

    @Test
    fun `a load that finishes after the language changed again is not applied`() {
        val grammars = GatedGrammars()
        var language by mutableStateOf(CodeLanguage.Rust)
        compose.setCodeViewContent {
            CodeViewBody(
                text = "x",
                language = language,
                readOnly = true,
                onEditor = { editor = it },
                loadGrammar = grammars.load,
            )
        }
        compose.runOnIdle { language = CodeLanguage.Go }
        compose.runOnIdle { assertFalse(editor().editorLanguage is TextMateLanguage) }

        grammars.gate(CodeLanguage.Rust).complete(Unit)
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertFalse(editor().editorLanguage is TextMateLanguage, "the stale Rust load was applied") }

        grammars.gate(CodeLanguage.Go).complete(Unit)
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { editor().editorLanguage is TextMateLanguage } }
    }

    @Test
    fun `the colors follow the app's dark or light theme`() {
        var dark by mutableStateOf(true)
        compose.setCodeViewContent(darkTheme = { dark }) {
            CodeViewBody(text = "x", language = CodeLanguage.PlainText, readOnly = true, onEditor = { editor = it })
        }
        compose.runOnIdle {
            val scheme = editor().colorScheme as TextMateColorScheme
            assertTrue(scheme.isDark)
        }
        dark = false
        compose.runOnIdle { assertFalse((editor().colorScheme as TextMateColorScheme).isDark) }
    }

    /** The 0-based line and column of the cursor, and whether text is selected. */
    private fun cursor(): Triple<Int, Int, Boolean> =
        editor().cursor.let { Triple(it.leftLine, it.leftColumn, it.isSelected) }

    /** Whether the 0-based [line] lies fully inside the editor's visible height. */
    private fun lineIsVisible(line: Int): Boolean {
        val bottom = editor().getCharOffsetY(line, 0)
        val top = bottom - editor().rowHeight
        return top >= 0f && bottom <= editor().height.toFloat()
    }

    /** Scrolls to the top as a user would, without moving the cursor. */
    private fun userScrollsToTop() {
        val scroller = editor().scroller
        scroller.forceFinished(true)
        scroller.startScroll(0, editor().offsetY, 0, -editor().offsetY, 0)
        scroller.abortAnimation()
    }

    private fun assertOpenedLine(line: Int) {
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithTag(CODE_VIEW_TAG).fetchSemanticsNodes()
                .any { it.config.getOrElseNullable(CodeViewOpenedLine) { null } == line }
        }
        compose.onNodeWithTag(CODE_VIEW_TAG).assert(SemanticsMatcher.expectValue(CodeViewOpenedLine, line))
    }

    @Test
    fun `a line opens with the cursor at its start and the keyboard closed`() {
        compose.setCodeViewContent {
            CodeViewBody(text = LONG_TEXT, language = CodeLanguage.Kotlin, line = 120, onEditor = { editor = it })
        }
        assertOpenedLine(120)
        compose.runOnIdle {
            assertEquals(Triple(119, 0, false), cursor())
            assertFalse(editor().isEditable)
            assertTrue(editor().offsetY > 0, "the editor did not scroll")
            assertTrue(lineIsVisible(119), "line 120 is not on screen")
        }
    }

    @Test
    fun `a line past the end opens at the last line`() {
        compose.setCodeViewContent {
            CodeViewBody(text = LONG_TEXT, language = CodeLanguage.PlainText, line = Int.MAX_VALUE, onEditor = { editor = it })
        }
        assertOpenedLine(LONG_LINES)
        compose.runOnIdle {
            assertEquals(Triple(LONG_LINES - 1, 0, false), cursor())
            assertTrue(lineIsVisible(LONG_LINES - 1), "the last line is not on screen")
        }
    }

    @Test
    fun `a missing, zero or negative line opens at the top`() {
        var line by mutableStateOf<Int?>(null)
        compose.setCodeViewContent {
            CodeViewBody(text = LONG_TEXT, language = CodeLanguage.PlainText, line = line, onEditor = { editor = it })
        }
        assertOpenedLine(1)
        compose.runOnIdle { assertEquals(Triple(0, 0, false), cursor()) }
        for (bad in listOf(80, 0, 80, Int.MIN_VALUE, 80, -1)) {
            line = bad
            assertOpenedLine(if (bad > 0) bad else 1)
            compose.runOnIdle {
                assertEquals(if (bad > 0) bad - 1 else 0, cursor().first)
                if (bad <= 0) assertEquals(0, editor().offsetY)
            }
        }
    }

    @Test
    fun `an empty text with a line opens at the top`() {
        compose.setCodeViewContent {
            CodeViewBody(text = "", language = CodeLanguage.PlainText, line = 9, onEditor = { editor = it })
        }
        assertOpenedLine(1)
        compose.runOnIdle { assertEquals(Triple(0, 0, false), cursor()) }
    }

    @Test
    fun `recomposition does not pull the user back to the line`() {
        var dark by mutableStateOf(false)
        var line by mutableStateOf<Int?>(120)
        var text by mutableStateOf(LONG_TEXT)
        compose.setCodeViewContent(darkTheme = { dark }) {
            CodeViewBody(text = text, language = CodeLanguage.PlainText, line = line, onEditor = { editor = it })
        }
        assertOpenedLine(120)
        compose.runOnIdle {
            editor().setSelection(3, 2)
            userScrollsToTop()
        }
        dark = true
        compose.runOnIdle {
            assertEquals(Triple(3, 2, false), cursor(), "the same line was applied again")
            assertEquals(0, editor().offsetY, "the same line scrolled the view again")
        }

        line = 50
        assertOpenedLine(50)
        compose.runOnIdle { assertEquals(49, cursor().first) }

        compose.runOnIdle { editor().setSelection(7, 0) }
        text = LONG_TEXT + "tail\n"
        compose.runOnIdle { assertEquals(Triple(49, 0, false), cursor(), "new text did not reopen at the line") }
    }

    @Test
    fun `typing in edit mode does not jump back to the line`() {
        compose.setCodeViewContent {
            var text by androidx.compose.runtime.remember { mutableStateOf(LONG_TEXT) }
            CodeViewBody(
                text = text,
                language = CodeLanguage.PlainText,
                readOnly = false,
                line = 100,
                onTextChange = { text = it },
                onEditor = { editor = it },
            )
        }
        assertOpenedLine(100)
        compose.runOnIdle {
            editor().setSelection(2, 0)
            editor().commitText("X")
        }
        compose.runOnIdle {
            assertTrue(editor().text.toString().contains("X"))
            assertEquals(2, cursor().first, "the edit was followed by a jump to the line")
        }
    }

    @Test
    fun `leaving the composition releases the editor`() {
        var shown by mutableStateOf(true)
        compose.setCodeViewContent {
            if (shown) CodeViewBody(text = "x", language = CodeLanguage.Kotlin, readOnly = true, onEditor = { editor = it })
        }
        compose.runOnIdle { assertFalse(editor().isReleased) }
        shown = false
        compose.runOnIdle { assertTrue(editor().isReleased) }
    }
}
