package io.github.thewisekd.phopencode.core.markdown

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every built-in text of `strings_markdown_link.xml`, as a user of that language reads it. */
data class LinkStrings(
    val confirmTitle: String,
    val confirmMessage: String,
    val open: String,
    val cancel: String,
    val blockedTitle: String,
    val blockedMessage: String,
    val failedTitle: String,
    val failedMessage: String,
    val close: String,
)

private const val LABEL = "the docs"
private const val TARGET = "https://example.com/docs?q=1#part"

/**
 * A link opens only after the user confirms a dialog that shows its full target, and only
 * for http and https. A subclass fixes the language with a qualifier.
 */
abstract class MarkdownLinkContract {
    @get:Rule
    val compose = createComposeRule()

    protected abstract val strings: LinkStrings

    private fun show(markdown: String, handler: RecordingUriHandler = RecordingUriHandler()): RecordingUriHandler {
        compose.setMarkdownContent(uriHandler = handler) { MarkdownView(markdown) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(LABEL, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        return handler
    }

    private fun assertNoDialog() {
        listOf(strings.confirmTitle, strings.blockedTitle, strings.failedTitle).forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test
    fun `rendering a link opens nothing and shows no dialog`() {
        val handler = show("See [$LABEL]($TARGET) and <https://example.org/auto>.")

        compose.waitForIdle()
        assertNoDialog()
        assertEquals(emptyList(), handler.opened)
    }

    @Test
    fun `tapping a link asks first and shows the full target`() {
        val handler = show("See [$LABEL]($TARGET).")

        compose.tapLink(LABEL)

        compose.onNodeWithText(strings.confirmTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.confirmMessage).assertExists()
        compose.onNodeWithText(TARGET).assertExists()
        assertEquals(emptyList(), handler.opened)
    }

    @Test
    fun `confirming opens the target once and closes the dialog`() {
        val handler = show("See [$LABEL]($TARGET).")

        compose.tapLink(LABEL)
        compose.onNodeWithText(strings.open).performClick()

        compose.runOnIdle { assertEquals(listOf(TARGET), handler.opened) }
        assertNoDialog()
    }

    @Test
    fun `cancelling opens nothing and closes the dialog`() {
        val handler = show("See [$LABEL]($TARGET).")

        compose.tapLink(LABEL)
        compose.onNodeWithText(strings.cancel).performClick()

        compose.runOnIdle { assertEquals(emptyList(), handler.opened) }
        assertNoDialog()
    }

    @Test
    fun `a reference link asks with its resolved target`() {
        val handler = show("See [$LABEL][ref].\n\n[ref]: $TARGET\n")

        compose.tapLink(LABEL)

        compose.onNodeWithText(TARGET).assertExists()
        compose.onNodeWithText(strings.open).performClick()
        compose.runOnIdle { assertEquals(listOf(TARGET), handler.opened) }
    }

    @Test
    fun `a link of another scheme is refused and shown, never opened`() {
        val handler = show("See [$LABEL](javascript:alert(1)).")

        compose.tapLink(LABEL)

        compose.onNodeWithText(strings.blockedTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.blockedMessage).assertExists()
        compose.onNodeWithText("javascript:alert(1)").assertExists()
        compose.onAllNodesWithText(strings.open).fetchSemanticsNodes()
            .let { assertTrue(it.isEmpty(), "a refused link offers to open") }
        compose.onNodeWithText(strings.close).performClick()

        compose.runOnIdle { assertEquals(emptyList(), handler.opened) }
        assertNoDialog()
    }

    @Test
    fun `a relative link is refused`() {
        val handler = show("See [$LABEL](docs/readme.md).")

        compose.tapLink(LABEL)

        compose.onNodeWithText(strings.blockedTitle).assertIsDisplayed()
        compose.onNodeWithText("docs/readme.md").assertExists()
        compose.runOnIdle { assertEquals(emptyList(), handler.opened) }
    }

    @Test
    fun `when nothing can open the link the user is told`() {
        val handler = show("See [$LABEL]($TARGET).", RecordingUriHandler(IllegalArgumentException("no app")))

        compose.tapLink(LABEL)
        compose.onNodeWithText(strings.open).performClick()

        compose.onNodeWithText(strings.failedTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.failedMessage).assertExists()
        compose.onNodeWithText(strings.close).performClick()
        assertNoDialog()
        compose.runOnIdle { assertEquals(listOf(TARGET), handler.opened) }
    }
}
