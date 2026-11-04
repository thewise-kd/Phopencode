package io.github.thewisekd.phopencode.core.markdown

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

private const val WAIT_MS = 5_000L

/** Code that is not a keyword in any language Highlights knows, after the keyword. */
private const val CODE_TAIL = " main() {}"

/** Parsing runs off the main thread, so wait for the result to show up. */
private fun ComposeContentTestRule.awaitText(text: String, substring: Boolean = false) {
    waitUntil(WAIT_MS) {
        onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class MarkdownRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `headings render as text without their markers`() {
        compose.setMarkdownContent { MarkdownView("# Title one\n\n## Title two\n\nBody") }

        compose.awaitText("Title one")
        compose.onNodeWithText("Title one", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Title two", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("#", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .let { assertTrue(it.isEmpty(), "a heading marker is shown") }
    }

    @Test
    fun `bullet and numbered lists render their items`() {
        compose.setMarkdownContent { MarkdownView("- apple\n- pear\n\n1. first\n2. second\n") }

        compose.awaitText("apple", substring = true)
        listOf("apple", "pear", "first", "second").forEach {
            compose.onNodeWithText(it, substring = true, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun `tables render header and body cells`() {
        compose.setMarkdownContent {
            MarkdownView("| Name | Size |\n| --- | --- |\n| alpha | 1 |\n| beta | 2 |\n")
        }

        compose.awaitText("alpha", substring = true)
        listOf("Name", "Size", "alpha", "beta").forEach {
            compose.onNodeWithText(it, substring = true, useUnmergedTree = true).assertExists()
        }
        compose.onAllNodesWithText("---", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .let { assertTrue(it.isEmpty(), "the table separator row is shown as text") }
    }

    @Test
    fun `a fenced code block renders its code without the fence`() {
        compose.setMarkdownContent { MarkdownView("Before\n\n```\nval x = 1\n```\n\nAfter") }

        compose.awaitText("val x = 1", substring = true)
        compose.onNodeWithText("Before", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("After", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("```", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .let { assertTrue(it.isEmpty(), "the fence is shown as text") }
    }

    @Test
    fun `an indented code block renders as plain code`() {
        compose.setMarkdownContent { MarkdownView("Text\n\n    fun main() {}\n") }

        compose.awaitText("fun main() {}", substring = true)
        val code = compose.onNodeWithText("fun main() {}", substring = true, useUnmergedTree = true)
        assertTrue(code.annotatedText().spanStyles.isEmpty(), "an indented block has no language to highlight")
    }

    @Test
    fun `each declared language highlights its fenced code`() {
        var markdown by mutableStateOf("")
        compose.setMarkdownContent { MarkdownView(markdown) }

        for ((name, language) in CodeLanguages.supported) {
            val keyword = KEYWORDS.getValue(language)
            val code = keyword + CODE_TAIL
            markdown = "```$name\n$code\n```"
            try {
                compose.waitUntil(WAIT_MS) {
                    compose.onAllNodesWithText(code, useUnmergedTree = true).fetchSemanticsNodes()
                        .any { node -> node.highlightsRange(0, keyword.length) }
                }
            } catch (timeout: ComposeTimeoutException) {
                throw AssertionError("`$name` does not highlight `$keyword`", timeout)
            }
        }
    }

    @Test
    fun `an unknown language renders as plain code`() {
        val code = "fun$CODE_TAIL"
        compose.setMarkdownContent { MarkdownView("```brainfuck\n$code\n```") }

        compose.awaitText(code)
        val text = compose.onNodeWithText(code, useUnmergedTree = true).annotatedText()
        assertTrue(text.spanStyles.isEmpty(), "unknown language got highlighted")
    }

    @Test
    fun `highlighting works in the dark theme too`() {
        val code = "fun$CODE_TAIL"
        compose.setMarkdownContent(darkTheme = true) { MarkdownView("```kotlin\n$code\n```") }

        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(code, useUnmergedTree = true).fetchSemanticsNodes()
                .any { it.highlightsRange(0, 3) }
        }
    }

    @Test
    fun `the root carries the public test tag`() {
        compose.setMarkdownContent { MarkdownView("Hello") }

        compose.awaitText("Hello")
        compose.onNodeWithTag(MARKDOWN_VIEW_TAG).assertIsDisplayed()
    }
}

/** A keyword per language that its grammar colors. */
private val KEYWORDS = mapOf(
    SyntaxLanguage.C to "return",
    SyntaxLanguage.CPP to "namespace",
    SyntaxLanguage.CSHARP to "class",
    SyntaxLanguage.COFFEESCRIPT to "unless",
    SyntaxLanguage.DART to "final",
    SyntaxLanguage.GO to "func",
    SyntaxLanguage.JAVA to "class",
    SyntaxLanguage.JAVASCRIPT to "function",
    SyntaxLanguage.KOTLIN to "fun",
    SyntaxLanguage.PERL to "my",
    SyntaxLanguage.PHP to "function",
    SyntaxLanguage.PYTHON to "def",
    SyntaxLanguage.RUBY to "def",
    SyntaxLanguage.RUST to "fn",
    SyntaxLanguage.SHELL to "pushd",
    SyntaxLanguage.SWIFT to "func",
    SyntaxLanguage.TYPESCRIPT to "interface",
)

private fun SemanticsNode.highlightsRange(start: Int, end: Int): Boolean =
    config[SemanticsProperties.Text].any { text ->
        text.spanStyles.any { it.start == start && it.end == end && it.item.color.isSpecified }
    }
