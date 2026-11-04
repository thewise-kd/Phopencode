package io.github.thewisekd.phopencode.core.markdown

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** A reply the way a model streams it: every block kind, a link, nesting and a long line. */
private val REPLY = """
    # Plan

    Some **bold**, _italic_, `inline code` and a [link](https://example.com/docs?q=1).

    - first
      - nested
    - second

    1. one
    2. two

    > a quote

    | Name | Size | Note |
    | :--- | ---: | --- |
    | alpha | 1 | ${"x".repeat(80)} |
    | beta | 2 | ok |

    ```kotlin
    fun main() {
        println("hi")
    }
    ```

    ${"word".repeat(60)}

    - [ ] task
    - [x] done
""".trimIndent()

/** Partial Markdown a stream stops on; each one must still render. */
private val PARTIALS = listOf(
    "```kotlin\nfun main() {\n    println(\"hi\"",
    "```",
    "~~~py\nprint(1)",
    "| a | b |\n| --",
    "| a | b |\n| --- | --- |\n| 1 |",
    "| a |",
    "[half link](https://exa",
    "[half link](",
    "[half",
    "![image](https://example.com/a.png",
    "**bold without end",
    "_italic",
    "`code",
    "<div>html",
    "> ",
    "1. ",
    "- ",
    "- [ ",
    "#",
    "######## too deep",
    "[ref]: https://example.com",
    "\u0000￿\uD83D",
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class MarkdownStreamingTest {
    @get:Rule
    val compose = createComposeRule()

    private fun assertRendersCleanly(what: String) {
        compose.waitForIdle()
        assertTrue(
            compose.onAllNodesWithTag(MARKDOWN_FALLBACK_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
            "parsing failed on $what",
        )
        val bounds = compose.onNodeWithTag(MARKDOWN_VIEW_TAG).getUnclippedBoundsInRoot()
        assertTrue(bounds.width <= CONTAINER_WIDTH + 0.5.dp, "the layout grew wider than its container on $what")
    }

    @Test
    fun `a reply growing one character at a time always renders`() {
        var markdown by mutableStateOf("")
        compose.setMarkdownContent { MarkdownBody(markdown, Modifier, parseImmediately = true) }

        for (end in 1..REPLY.length) {
            markdown = REPLY.substring(0, end)
            assertRendersCleanly("prefix $end")
        }
        compose.onAllNodesWithText("alpha", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .let { assertTrue(it.isNotEmpty(), "the finished table is missing") }
    }

    @Test
    fun `partial Markdown a stream stops on always renders`() {
        var markdown by mutableStateOf("")
        compose.setMarkdownContent { MarkdownBody(markdown, Modifier, parseImmediately = true) }

        PARTIALS.forEachIndexed { index, partial ->
            markdown = partial
            assertRendersCleanly("partial $index")
        }
    }

    @Test
    fun `the public view keeps up with a stream and shows the final text`() {
        var markdown by mutableStateOf("")
        compose.setMarkdownContent { MarkdownView(markdown) }

        for (end in 1..REPLY.length step 7) {
            markdown = REPLY.substring(0, end)
            compose.mainClock.advanceTimeByFrame()
        }
        markdown = REPLY
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("println", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertRendersCleanly("the final reply")
    }
}
