package io.github.thewisekd.phopencode.feature.chat.ui.palette

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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

/** The texts of the command palette and the attachment chips in one language. */
internal data class ExpectedPaletteStrings(
    val titleSlash: String,
    val titleAt: String,
    val close: String,
    /** Each row of [SLASH] and [AT] but the built-ins, its texts in order. */
    val rows: Map<PaletteItem, List<String>>,
    /** Each built-in's row, its texts in order: name, what it does, its aliases if any, its kind. */
    val builtIns: Map<BuiltInCommand, List<String>>,
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
    /** Each chip of [ATTACHMENTS], its texts in order. */
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
        row.assertTextEquals(*texts.toTypedArray())
        val label = row.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label
        val runs = item is PaletteItem.Command || item is PaletteItem.BuiltIn
        assertEquals(if (runs) strings.run else strings.attach, label)
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
            compose.onNodeWithTag(PaletteTags.chip(attachment)).performScrollTo()
                .assertTextEquals(*strings.chips.getValue(attachment).toTypedArray())
            compose.onNodeWithContentDescription(strings.remove.getValue(attachment)).performScrollTo().assertIsDisplayed()
        }
    }
}
