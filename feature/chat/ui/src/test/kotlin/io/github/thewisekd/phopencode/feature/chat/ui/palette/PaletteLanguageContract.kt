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
    /** Each row of [SLASH] and [AT], its texts in order. */
    val rows: Map<PaletteItem, List<String>>,
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
    fun `the slash palette is titled and tells commands from skills`() {
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

    private fun assertRow(item: PaletteItem) {
        val row = compose.onNodeWithTag(PaletteTags.item(item)).performScrollTo()
        row.assertTextEquals(*strings.rows.getValue(item).toTypedArray())
        val label = row.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label
        assertEquals(if (item is PaletteItem.Command) strings.run else strings.attach, label)
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
    fun `every failed command has its own message`() {
        val harness = compose.setPalette(SLASH)
        strings.commandErrors.forEach { (failure, message) ->
            harness.state.value = SLASH.copy(error = PaletteError(PaletteAction.RunCommand, failure))
            compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        }
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
