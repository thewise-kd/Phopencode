package io.github.thewisekd.phopencode.feature.files.ui.viewer

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import io.github.thewisekd.phopencode.core.codeview.CodeViewOpenedLine
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse

private const val WAIT_MS = 5_000L
private const val LINES = 200
private val LONG_CODE = (1..LINES).joinToString("\n") { "val x$it = $it" }
private val LONG_MARKDOWN = (1..LINES).joinToString("\n") { "Paragraph $it" }

/**
 * The state's line reaches the code view, which reports on its node the 1-based line it opened at.
 * That the editor scrolls the line into view is core/codeview's to test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ViewerLineTest {
    @get:Rule
    val compose = createComposeRule()

    private fun assertOpenedAt(line: Int) {
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithTag(ViewerTags.CODE, useUnmergedTree = true).fetchSemanticsNodes()
                .any { it.config.getOrElseNullable(CodeViewOpenedLine) { null } == line }
        }
        compose.onNodeWithTag(ViewerTags.CODE, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(CodeViewOpenedLine, line))
    }

    @Test
    fun `code opens at the state's line`() {
        compose.setViewer(loaded(CODE_PATH, ViewerContent.Text(LONG_CODE)).copy(line = 150), routeOf(CODE_PATH, line = 150))

        assertOpenedAt(150)
    }

    @Test
    fun `raw Markdown opens at the state's line`() {
        val state = loaded(MARKDOWN_PATH, ViewerContent.Text(LONG_MARKDOWN, markdown = true))
        compose.setViewer(state.copy(line = 42, mode = ViewerMode.Raw), routeOf(MARKDOWN_PATH, line = 42))

        assertOpenedAt(42)
    }

    @Test
    fun `no line opens at the top`() {
        compose.setViewer(loaded(CODE_PATH, ViewerContent.Text(LONG_CODE)))

        assertOpenedAt(1)
    }

    @Test
    fun `a line past the end opens at the last line without crashing`() {
        compose.setViewer(loaded(CODE_PATH, ViewerContent.Text(LONG_CODE)).copy(line = LINES * 10))

        assertOpenedAt(LINES)
    }

    @Test
    fun `a zero or negative line opens at the top`() {
        val harness = compose.setViewer(loaded(CODE_PATH, ViewerContent.Text(LONG_CODE)).copy(line = 0))
        assertOpenedAt(1)

        harness.state.value = harness.state.value.copy(line = 120)
        assertOpenedAt(120)

        harness.state.value = harness.state.value.copy(line = -3)
        assertOpenedAt(1)
    }

    @Test
    fun `a line that arrives with the loaded text is applied`() {
        val harness = compose.setViewer(loaded(CODE_PATH, ViewerContent.Text("")).copy(content = null, loading = true))

        harness.state.value = loaded(CODE_PATH, ViewerContent.Text(LONG_CODE)).copy(line = 77)
        assertOpenedAt(77)
    }

    @Test
    fun `rendered Markdown shows no code view, whatever the line`() {
        val state = loaded(MARKDOWN_PATH, ViewerContent.Text(LONG_MARKDOWN, markdown = true))
        compose.setViewer(state.copy(line = 42), routeOf(MARKDOWN_PATH, line = 42))

        compose.awaitText("Paragraph 1", substring = true)
        compose.onNodeWithTag(ViewerTags.MARKDOWN).assertExists()
        assertFalse(compose.hasTag(ViewerTags.CODE))
    }
}
