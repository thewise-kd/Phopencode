package io.github.thewisekd.phopencode.feature.chat.ui.tool

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolSection
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolSummary
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the tool card in one language. */
internal data class ExpectedToolStrings(
    val status: Map<ToolCardStatus, String>,
    val shell: (String) -> String,
    val edit: (String) -> String,
    val read: (String) -> String,
    val search: (String) -> String,
    val searchIn: (pattern: String, path: String) -> String,
    val generic: (String) -> String,
    val input: String,
    val output: String,
    val expanded: String,
    val collapsed: String,
    val showInput: String,
    val hideInput: String,
    val showOutput: String,
    val hideOutput: String,
    val moreLines: (Int) -> String,
    val moreCharacters: (Int) -> String,
    val error: (String) -> String,
)

/** What the card says, the same in each language; a subclass sets the language and its texts. */
abstract class ToolLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedToolStrings

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()

    /** The card a test draws once and then changes; content can be set only once per test. */
    private var shown: ToolHarness? = null

    private fun show(state: ToolCardState) {
        val current = shown
        if (current == null) shown = compose.setTool(state) else current.state.value = state
        compose.waitForIdle()
    }

    private fun summaryOf(summary: ToolSummary): String {
        show(shellCard().copy(summary = summary))
        return node(TOOL_SUMMARY_TAG).text()
    }

    @Test
    fun `each status is named for screen readers`() {
        for (status in ToolCardStatus.entries) {
            show(shellCard(status = status))
            val description = node(TOOL_STATUS_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
            assertEquals(listOf(strings.status.getValue(status)), description, "status $status")
        }
    }

    @Test
    fun `each kind of call has its summary line`() {
        assertEquals(strings.shell(COMMAND), summaryOf(ToolSummary.Shell(COMMAND)))
        assertEquals(strings.edit("src/Çalış.kt"), summaryOf(ToolSummary.Edit("src/Çalış.kt")))
        assertEquals(strings.read("docs/ödev.md"), summaryOf(ToolSummary.Read("docs/ödev.md")))
        assertEquals(strings.search("TODO"), summaryOf(ToolSummary.Search("TODO")))
        assertEquals(strings.searchIn("TODO", "src/"), summaryOf(ToolSummary.Search("TODO", "src/")))
        assertEquals(strings.generic("webfetch"), summaryOf(ToolSummary.Generic("webfetch")))
    }

    @Test
    fun `a command over several lines is summed up on one line`() {
        assertEquals(strings.shell("cd app && ./gradlew test"), summaryOf(ToolSummary.Shell("cd app &&\n./gradlew test")))
    }

    @Test
    fun `an edit shows the lines it added and removed when known`() {
        show(shellCard().copy(summary = ToolSummary.Edit("a.kt", additions = 12, deletions = 3)))
        assertEquals("+12 −3", node(TOOL_CHANGES_TAG).text())

        show(shellCard().copy(summary = ToolSummary.Edit("a.kt", additions = 4)))
        assertEquals("+4", node(TOOL_CHANGES_TAG).text())

        show(shellCard().copy(summary = ToolSummary.Edit("a.kt")))
        compose.onNodeWithTag(TOOL_CHANGES_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `section headers say their name, whether they are open and what a tap does`() {
        show(shellCard(input = "in", output = "out"))
        assertSection(ToolSection.Input, strings.input, strings.collapsed, strings.showInput)
        assertSection(ToolSection.Output, strings.output, strings.collapsed, strings.showOutput)

        show(shellCard(input = "in", output = "out").copy(expanded = setOf(ToolSection.Input, ToolSection.Output)))
        assertSection(ToolSection.Input, strings.input, strings.expanded, strings.hideInput)
        assertSection(ToolSection.Output, strings.output, strings.expanded, strings.hideOutput)
    }

    private fun assertSection(section: ToolSection, title: String, state: String, action: String) {
        // The merged tree: the header is one button that reads its title.
        val config = compose.onNodeWithTag(toolSectionTag(section)).performScrollTo().fetchSemanticsNode().config
        assertEquals(listOf(title), config.getOrNull(SemanticsProperties.Text)?.map { it.text }, "title of $section")
        assertEquals(state, config.getOrNull(SemanticsProperties.StateDescription), "state of $section")
        assertEquals(action, config.getOrNull(SemanticsActions.OnClick)?.label, "action of $section")
    }

    @Test
    fun `a shortened section says how much is left`() {
        val open = setOf(ToolSection.Output)
        show(shellCard(output = numberedLines(TOOL_MAX_LINES + 1)).copy(expanded = open))
        assertEquals(strings.moreLines(1), node(toolMoreTag(ToolSection.Output)).text())

        show(shellCard(output = numberedLines(TOOL_MAX_LINES + 5)).copy(expanded = open))
        assertEquals(strings.moreLines(5), node(toolMoreTag(ToolSection.Output)).text())

        show(shellCard(output = "q".repeat(TOOL_MAX_CHARS + 1)).copy(expanded = open))
        assertEquals(strings.moreCharacters(1), node(toolMoreTag(ToolSection.Output)).text())

        show(shellCard(output = "q".repeat(TOOL_MAX_CHARS + 9)).copy(expanded = open))
        assertEquals(strings.moreCharacters(9), node(toolMoreTag(ToolSection.Output)).text())
    }

    @Test
    fun `an error says what went wrong`() {
        show(shellCard(status = ToolCardStatus.Failed).copy(error = StructuredError(type = "tool", message = "çöktü")))

        assertEquals(strings.error("çöktü"), node(TOOL_ERROR_TAG).text())
    }
}
