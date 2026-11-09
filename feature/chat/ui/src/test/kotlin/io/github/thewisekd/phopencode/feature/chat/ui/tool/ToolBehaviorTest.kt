package io.github.thewisekd.phopencode.feature.chat.ui.tool

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the card draws and sends, in English: the texts of both languages are in `ToolLanguageContract`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ToolBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    /** Scrolled into view first: at API 35+ the insets push the content down. */
    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()

    private val both = setOf(ToolSection.Input, ToolSection.Output)

    // The row

    @Test
    fun `a call starts as one slim row with no details`() {
        compose.setTool(shellCard(input = INPUT, output = OUTPUT))
        compose.waitForIdle()

        node(TOOL_HEADER_TAG).assertIsDisplayed()
        node(TOOL_SUMMARY_TAG).assertIsDisplayed()
        node(TOOL_STATUS_TAG).assertIsDisplayed()
        for (section in ToolSection.entries) {
            compose.onNodeWithTag(toolSectionTag(section), useUnmergedTree = true).assertDoesNotExist()
        }
        val card = compose.onNodeWithTag(CARD_TAG).fetchSemanticsNode()
        val height = with(card.layoutInfo.density) { card.boundsInRoot.height.toDp() }
        assertTrue(height <= 56.dp, "a collapsed call is one row, was $height")
    }

    @Test
    fun `tapping the row opens and closes its details without asking the presenter`() {
        val harness = compose.setTool(shellCard(input = INPUT, output = OUTPUT))

        node(TOOL_HEADER_TAG).performClick()
        node(toolSectionTag(ToolSection.Input)).assertIsDisplayed()
        node(toolSectionTag(ToolSection.Output)).assertIsDisplayed()

        node(TOOL_HEADER_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(toolSectionTag(ToolSection.Input), useUnmergedTree = true).assertDoesNotExist()
        assertTrue(harness.intents.isEmpty(), "the row's own toggle sends nothing: ${harness.intents}")
    }

    @Test
    fun `the row is a button big enough to touch when it has details`() {
        compose.setTool(shellCard(input = INPUT))

        compose.onNodeWithTag(TOOL_HEADER_TAG).performScrollTo()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertTouchSizeIsAtLeast(48.dp)
    }

    @Test
    fun `a row with nothing to show is not a button`() {
        compose.setTool(shellCard(status = ToolCardStatus.Preparing))

        compose.onNodeWithTag(TOOL_HEADER_TAG).performScrollTo().assertHasNoClickAction()
    }

    @Test
    fun `a failed call opens with its error, and a call with an open section opens with it`() {
        val harness = compose.setTool(
            shellCard(status = ToolCardStatus.Failed, input = INPUT).copy(error = StructuredError(type = "tool", message = "boom")),
        )
        node(TOOL_ERROR_TAG).assertIsDisplayed()
        node(toolSectionTag(ToolSection.Input)).assertIsDisplayed()

        harness.state.value = shellCard(input = INPUT, output = OUTPUT)
        node(TOOL_HEADER_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(toolSectionTag(ToolSection.Input), useUnmergedTree = true).assertDoesNotExist()
        harness.state.value = harness.state.value.copy(expanded = setOf(ToolSection.Output))
        node(toolTextTag(ToolSection.Output)).assertIsDisplayed()
    }

    @Test
    fun `an error that arrives opens a row the user closed with a section open, and a later close holds`() {
        val harness = compose.setTool(shellCard(input = INPUT, output = OUTPUT).copy(expanded = setOf(ToolSection.Output)))
        node(toolTextTag(ToolSection.Output)).assertIsDisplayed()
        node(TOOL_HEADER_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(toolSectionTag(ToolSection.Output), useUnmergedTree = true).assertDoesNotExist()

        harness.state.value = harness.state.value.copy(
            status = ToolCardStatus.Failed,
            error = StructuredError(type = "tool", message = "exit code 2"),
        )
        assertEquals("Error: exit code 2", node(TOOL_ERROR_TAG).text())

        node(TOOL_HEADER_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TOOL_ERROR_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a failed row the user closed stays closed when it leaves the screen and comes back`() {
        val shown = mutableStateOf(true)
        val card = shellCard(status = ToolCardStatus.Failed, input = INPUT, output = OUTPUT).copy(
            error = StructuredError(type = "tool", message = "exit code 2"),
            expanded = setOf(ToolSection.Output),
        )
        compose.setContent {
            // As the timeline's lazy list does: an item's saved state outlives its leaving the screen.
            val holder = rememberSaveableStateHolder()
            PhopencodeTheme(darkTheme = false) {
                Surface {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (shown.value) {
                            holder.SaveableStateProvider(KEY.value) {
                                ToolCard(KEY, card, {}, Modifier.fillMaxWidth().testTag(CARD_TAG))
                            }
                        }
                    }
                }
            }
        }
        node(TOOL_ERROR_TAG).assertIsDisplayed()
        node(TOOL_HEADER_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TOOL_ERROR_TAG, useUnmergedTree = true).assertDoesNotExist()

        shown.value = false
        compose.waitForIdle()
        compose.onNodeWithTag(TOOL_HEADER_TAG, useUnmergedTree = true).assertDoesNotExist()
        shown.value = true
        compose.waitForIdle()

        node(TOOL_HEADER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TOOL_ERROR_TAG, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(toolSectionTag(ToolSection.Output), useUnmergedTree = true).assertDoesNotExist()
    }

    // Sections

    @Test
    fun `an open card shows its summary and section headers but no bodies`() {
        compose.setTool(shellCard(input = INPUT, output = OUTPUT))

        node(TOOL_HEADER_TAG).assertIsDisplayed().performClick()
        node(toolSectionTag(ToolSection.Input)).assertIsDisplayed()
        node(toolSectionTag(ToolSection.Output)).assertIsDisplayed()
        compose.onNodeWithTag(toolTextTag(ToolSection.Input), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(toolTextTag(ToolSection.Output), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `expanded sections show the input and the output`() {
        compose.setTool(shellCard(input = INPUT, output = OUTPUT).copy(expanded = both))

        assertEquals(INPUT, node(toolTextTag(ToolSection.Input)).text())
        assertEquals(OUTPUT, node(toolTextTag(ToolSection.Output)).text())
    }

    @Test
    fun `one expanded section leaves the other closed`() {
        compose.setTool(shellCard(input = INPUT, output = OUTPUT).copy(expanded = setOf(ToolSection.Output)))

        node(toolTextTag(ToolSection.Output)).assertIsDisplayed()
        compose.onNodeWithTag(toolTextTag(ToolSection.Input), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `tapping a section header asks to toggle it and leaves the state to the presenter`() {
        val harness = compose.setTool(shellCard(input = INPUT, output = OUTPUT))
        node(TOOL_HEADER_TAG).performClick()

        node(toolSectionTag(ToolSection.Input)).performClick()
        node(toolSectionTag(ToolSection.Output)).performClick()

        assertEquals(
            listOf<ToolIntent>(ToolIntent.Toggle(KEY, ToolSection.Input), ToolIntent.Toggle(KEY, ToolSection.Output)),
            harness.intents,
        )
        compose.onNodeWithTag(toolTextTag(ToolSection.Input), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `sections open and close as the state says`() {
        val harness = compose.setTool(shellCard(input = INPUT, output = OUTPUT))

        harness.state.value = harness.state.value.copy(expanded = setOf(ToolSection.Input))
        assertEquals(INPUT, node(toolTextTag(ToolSection.Input)).text())

        harness.state.value = harness.state.value.copy(expanded = emptySet())
        compose.waitForIdle()
        compose.onNodeWithTag(toolTextTag(ToolSection.Input), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `section headers are buttons big enough to touch`() {
        compose.setTool(shellCard(input = INPUT, output = OUTPUT))
        node(TOOL_HEADER_TAG).performClick()

        for (section in ToolSection.entries) {
            node(toolSectionTag(section))
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertTouchSizeIsAtLeast(48.dp)
        }
    }

    @Test
    fun `a call with no input or output yet has no sections`() {
        compose.setTool(shellCard(status = ToolCardStatus.Preparing).copy(expanded = both))

        node(TOOL_HEADER_TAG).assertIsDisplayed()
        for (section in ToolSection.entries) {
            compose.onNodeWithTag(toolSectionTag(section), useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun `output that streams in grows the open section`() {
        val harness = compose.setTool(shellCard(input = INPUT, output = "line 1").copy(expanded = both))

        harness.state.value = harness.state.value.copy(output = numberedLines(3))

        assertEquals(numberedLines(3), node(toolTextTag(ToolSection.Output)).text())
    }

    // Shortening

    @Test
    fun `long output is shortened with a note of how many lines are left`() {
        compose.setTool(shellCard(input = INPUT, output = numberedLines(TOOL_MAX_LINES + 7)).copy(expanded = both))

        assertEquals(numberedLines(TOOL_MAX_LINES), node(toolTextTag(ToolSection.Output)).text())
        assertEquals("7 more lines", node(toolMoreTag(ToolSection.Output)).text())
    }

    @Test
    fun `one more line is said in the singular`() {
        compose.setTool(shellCard(input = INPUT, output = numberedLines(TOOL_MAX_LINES + 1)).copy(expanded = both))

        assertEquals("1 more line", node(toolMoreTag(ToolSection.Output)).text())
    }

    @Test
    fun `one very long line is shortened with a note of how many characters are left`() {
        compose.setTool(shellCard(input = INPUT, output = "z".repeat(TOOL_MAX_CHARS + 12)).copy(expanded = both))

        assertEquals(TOOL_MAX_CHARS, node(toolTextTag(ToolSection.Output)).text().length)
        assertEquals("12 more characters", node(toolMoreTag(ToolSection.Output)).text())
    }

    @Test
    fun `long input is shortened too`() {
        compose.setTool(shellCard(input = numberedLines(TOOL_MAX_LINES + 3), output = OUTPUT).copy(expanded = both))

        assertEquals(numberedLines(TOOL_MAX_LINES), node(toolTextTag(ToolSection.Input)).text())
        assertEquals("3 more lines", node(toolMoreTag(ToolSection.Input)).text())
    }

    @Test
    fun `short output has no note`() {
        compose.setTool(shellCard(input = INPUT, output = OUTPUT).copy(expanded = both))

        node(toolTextTag(ToolSection.Output)).assertIsDisplayed()
        compose.onNodeWithTag(toolMoreTag(ToolSection.Output), useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * The timeline follows the latest item by scrolling itself (P6.4); a card that took vertical
     * scroll would break that. Horizontal scrolling for long lines is fine.
     */
    @Test
    fun `the card never scrolls vertically, only long lines scroll sideways`() {
        val wide = "w".repeat(400)
        compose.setTool(
            shellCard(input = "$wide\n${numberedLines(200)}", output = "$wide\n${numberedLines(500)}").copy(
                status = ToolCardStatus.Failed,
                expanded = both,
                error = StructuredError(type = "tool", message = numberedLines(30)),
            ),
        )
        compose.waitForIdle()

        val inCard = hasTestTag(CARD_TAG) or hasAnyAncestor(hasTestTag(CARD_TAG))
        val nodes = compose.onAllNodes(inCard, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        val vertical = nodes.filter { SemanticsProperties.VerticalScrollAxisRange in it.config }
        assertTrue(vertical.isEmpty(), "nodes that scroll vertically: ${vertical.map { it.config }}")
        val editable = nodes.filter { SemanticsProperties.EditableText in it.config }
        assertTrue(editable.isEmpty(), "the card must not hold a text field")
        // The check above can see scroll semantics: the long lines do scroll sideways.
        assertTrue(nodes.any { SemanticsProperties.HorizontalScrollAxisRange in it.config })
    }

    // Errors

    @Test
    fun `a failed call shows its error text`() {
        compose.setTool(
            shellCard(status = ToolCardStatus.Failed).copy(error = StructuredError(type = "tool", message = "exit code 2")),
        )

        assertEquals("Error: exit code 2", node(TOOL_ERROR_TAG).text())
    }

    @Test
    fun `an error with no message shows its type`() {
        compose.setTool(shellCard(status = ToolCardStatus.Failed).copy(error = StructuredError(type = "tool.aborted")))

        assertEquals("Error: tool.aborted", node(TOOL_ERROR_TAG).text())
    }

    @Test
    fun `a call with no error shows none`() {
        compose.setTool(shellCard(status = ToolCardStatus.Completed, output = OUTPUT))

        node(TOOL_HEADER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TOOL_ERROR_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    // Status

    @Test
    fun `each status has its own still icon`() {
        val icons = ToolCardStatus.entries.map { toolStatusIcon(it) }

        assertEquals(icons.size, icons.toSet().size, "statuses that share an icon")
        assertFalse(icons.any { it.name.isBlank() })
    }

    private companion object {
        const val INPUT = "{\n  \"command\": \"ls -la çıktı/\"\n}"
        const val OUTPUT = "total 0\ndrwxr-xr-x  2 ace ace 40 çıktı"
    }
}
