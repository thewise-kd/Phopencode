package io.github.thewisekd.phopencode.feature.chat.ui.palette

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.BuiltInCommand
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.BuiltInNotice
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteItem
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteTrigger
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PromptAttachment
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The texts of the command palette and the attachment chips in one language. */
internal data class ExpectedPaletteStrings(
    val titleSlash: String,
    val titleAt: String,
    val close: String,
    /**
     * Each row of [SLASH] and [AT] but the built-ins, its texts in order, then its kind, which the
     * row's icon shows and a screen reader reads first.
     */
    val rows: Map<PaletteItem, List<String>>,
    /** Each built-in's row, its texts in order: name, what it does, its aliases if any; then its kind, as for [rows]. */
    val builtIns: Map<BuiltInCommand, List<String>>,
    /** The header over each kind of item. */
    val sections: Map<PaletteSection, String>,
    /** Why a built-in did not run. */
    val notices: Map<BuiltInNotice, String>,
    val noticeDismiss: String,
    val run: String,
    val attach: String,
    val loading: String,
    val empty: String,
    val running: String,
    val loadErrors: Map<ChatFailure, String>,
    val commandErrors: Map<ChatFailure, String>,
    val retry: String,
    val dismiss: String,
    /** Each chip of [ATTACHMENTS]: its kind, which its icon shows and a screen reader reads, then what it holds. */
    val chips: Map<PromptAttachment, List<String>>,
    /** The remove button of each chip of [ATTACHMENTS]. */
    val remove: Map<PromptAttachment, String>,
)

/** What the palette and the chips say, the same in each language; a subclass sets the language and its texts. */
abstract class PaletteLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedPaletteStrings

    @Test
    fun `the slash palette is titled and tells commands, built-ins and skills apart`() {
        compose.setPalette(SLASH)

        compose.onNodeWithTag(PaletteTags.TITLE).performScrollTo().assertTextEquals(strings.titleSlash)
        compose.onNodeWithContentDescription(strings.close).performScrollTo().assertIsDisplayed()
        SLASH.items.forEach { assertRow(it) }
    }

    @Test
    fun `the at palette is titled and tells agents, files and folders apart`() {
        compose.setPalette(AT)

        compose.onNodeWithTag(PaletteTags.TITLE).performScrollTo().assertTextEquals(strings.titleAt)
        AT.items.forEach { assertRow(it) }
    }

    @Test
    fun `every built-in says what it does, its aliases and its kind, and runs`() {
        compose.setPalette(BUILT_INS)

        BUILT_INS.items.forEach { assertRow(it) }
        assertEquals(BuiltInCommand.entries.toSet(), strings.builtIns.keys)
    }

    @Test
    fun `every notice has its own message and a dismiss, open or closed`() {
        val harness = compose.setPalette(SLASH)
        strings.notices.forEach { (notice, message) ->
            harness.state.value = SLASH.copy(notice = notice)
            compose.onNodeWithTag(PaletteTags.NOTICE).performScrollTo().assertTextEquals(message)
            harness.state.value = PaletteState(notice = notice)
            compose.onNodeWithTag(PaletteTags.NOTICE).performScrollTo().assertTextEquals(message)
        }
        compose.onNodeWithContentDescription(strings.noticeDismiss).performScrollTo().assertIsDisplayed()
        assertEquals(BuiltInNotice.entries.toSet(), strings.notices.keys)
    }

    private fun assertRow(item: PaletteItem) {
        val row = compose.onNodeWithTag(PaletteTags.item(item)).performScrollTo()
        val texts = if (item is PaletteItem.BuiltIn) strings.builtIns.getValue(item.command) else strings.rows.getValue(item)
        // Read as TalkBack reads it: the kind first, then every line, and no description that would hide them.
        row.assertTextEquals(texts.last(), *texts.dropLast(1).toTypedArray())
        assertSpokenInFull(row, texts.last(), texts.dropLast(1))
        val label = row.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label
        val runs = item is PaletteItem.Command || item is PaletteItem.BuiltIn
        assertEquals(if (runs) strings.run else strings.attach, label)
    }

    /**
     * What TalkBack speaks for a merged [node]: its content description, if any, else its texts. It
     * holds [kind] first and then every one of [lines], so a description never hides the name.
     */
    private fun assertSpokenInFull(node: SemanticsNodeInteraction, kind: String, lines: List<String>) {
        val config = node.fetchSemanticsNode().config
        val described = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        val spoken = described.ifEmpty { config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        assertEquals(listOf(kind) + lines, spoken, "spoken output")
    }

    @Test
    fun `each kind of item sits under its own header, in the list's order`() {
        val harness = compose.setPalette(SLASH)
        assertSections(listOf(PaletteSection.Commands to INIT, PaletteSection.BuiltIns to NEW, PaletteSection.Skills to PDF))

        harness.state.value = AT
        assertSections(listOf(PaletteSection.Agents to EXPLORE, PaletteSection.Files to SOURCE))
        assertEquals(PaletteSection.entries.toSet(), strings.sections.keys)
    }

    /** Each section's header shows its name as a heading, after the one before and right before its first item. */
    private fun assertSections(sections: List<Pair<PaletteSection, PaletteItem>>) {
        var last = (-1).dp
        sections.forEach { (section, first) ->
            // Unclipped: the rows scroll inside the card, so a later one may be out of view.
            val header = compose.onNodeWithTag(PaletteTags.section(section)).performScrollTo()
                .assertTextEquals(strings.sections.getValue(section))
                .assert(isHeading())
                .getUnclippedBoundsInRoot()
            val item = compose.onNodeWithTag(PaletteTags.item(first)).getUnclippedBoundsInRoot()
            assertTrue(header.top > last, "$section is out of order")
            assertTrue(header.bottom <= item.top + 1.dp, "$section at $header is not above $first at $item")
            last = header.top
        }
        compose.onAllNodes(isHeading()).assertCountEquals(sections.size + 1)
    }

    @Test
    fun `the palette says when it is loading, finds nothing or runs a command`() {
        val harness = compose.setPalette(PaletteState(trigger = PaletteTrigger.At, loading = true))
        compose.onNodeWithText(strings.loading).performScrollTo().assertIsDisplayed()

        harness.state.value = PaletteState(trigger = PaletteTrigger.At)
        compose.onNodeWithText(strings.empty).performScrollTo().assertIsDisplayed()

        harness.state.value = SLASH.copy(running = true)
        compose.onNodeWithText(strings.running).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `every failed list has its own message, a retry and a dismiss`() {
        val harness = compose.setPalette(SLASH)
        strings.loadErrors.forEach { (failure, message) ->
            harness.state.value = SLASH.copy(error = PaletteError(PaletteAction.Load, failure))
            compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(strings.retry).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.dismiss).performScrollTo().assertIsDisplayed()
        assertEquals(ChatFailure.entries.toSet(), strings.loadErrors.keys)
    }

    @Test
    fun `every failed command has its own message, open or closed`() {
        val harness = compose.setPalette(SLASH)
        strings.commandErrors.forEach { (failure, message) ->
            harness.state.value = SLASH.copy(error = PaletteError(PaletteAction.RunCommand, failure))
            compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
            // A command sent with text closes the palette; its failure still shows.
            harness.state.value = PaletteState(error = PaletteError(PaletteAction.RunCommand, failure))
            compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithContentDescription(strings.dismiss).performScrollTo().assertIsDisplayed()
        assertEquals(ChatFailure.entries.toSet(), strings.commandErrors.keys)
    }

    @Test
    fun `each chip names its kind and what it holds, with a remove button that says what it removes`() {
        compose.setChips(ATTACHMENTS)

        ATTACHMENTS.forEach { attachment ->
            val texts = strings.chips.getValue(attachment)
            val chip = compose.onNodeWithTag(PaletteTags.chip(attachment)).performScrollTo()
                .assertTextEquals(*texts.toTypedArray())
            assertSpokenInFull(chip, texts.first(), texts.drop(1))
            compose.onNodeWithContentDescription(strings.remove.getValue(attachment)).performScrollTo().assertIsDisplayed()
        }
    }
}
