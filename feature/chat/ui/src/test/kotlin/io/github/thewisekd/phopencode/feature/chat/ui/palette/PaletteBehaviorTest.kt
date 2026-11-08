package io.github.thewisekd.phopencode.feature.chat.ui.palette

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.BuiltInNotice
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteItem
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PromptAttachment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the palette and the chips send and show, in English: the texts of both languages are in `PaletteLanguageContract`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class PaletteBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    // The palette

    @Test
    fun `a closed palette draws nothing, keeping only its empty slot`() {
        val harness = compose.setPalette(PaletteState(items = listOf(INIT)))
        assertEmptySlot(PALETTE_TAG)
        compose.onNodeWithTag(PaletteTags.item(INIT)).assertDoesNotExist()

        harness.state.value = SLASH
        compose.onNodeWithTag(PaletteTags.item(INIT)).performScrollTo().assertHasClickAction()

        harness.state.value = SLASH.copy(trigger = null)
        assertEmptySlot(PALETTE_TAG)
    }

    @Test
    fun `tapping a row sends Pick with its item and nothing else`() {
        val harness = compose.setPalette(SLASH)
        compose.onNodeWithTag(PaletteTags.item(REVIEW)).performScrollTo().performClick()
        compose.onNodeWithTag(PaletteTags.item(PDF)).performScrollTo().performClick()

        harness.state.value = AT
        compose.onNodeWithTag(PaletteTags.item(EXPLORE)).performScrollTo().performClick()
        compose.onNodeWithTag(PaletteTags.item(FOLDER)).performScrollTo().performClick()

        assertEquals(
            listOf<PaletteIntent>(
                PaletteIntent.Pick(REVIEW),
                PaletteIntent.Pick(PDF),
                PaletteIntent.Pick(EXPLORE),
                PaletteIntent.Pick(FOLDER),
            ),
            harness.intents,
        )
    }

    @Test
    fun `tapping a built-in's row sends Pick with it`() {
        val harness = compose.setPalette(SLASH)

        compose.onNodeWithTag(PaletteTags.item(NEW)).performScrollTo().performClick()
        compose.onNodeWithTag(PaletteTags.item(UNDO)).performScrollTo().performClick()

        assertEquals(listOf<PaletteIntent>(PaletteIntent.Pick(NEW), PaletteIntent.Pick(UNDO)), harness.intents)
    }

    @Test
    fun `a notice shows while closed too, and its button sends DismissNotice`() {
        val harness = compose.setPalette(PaletteState(notice = BuiltInNotice.NothingToRedo))

        compose.onNodeWithTag(PaletteTags.TITLE).assertDoesNotExist()
        compose.onNodeWithTag(PaletteTags.NOTICE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(PaletteTags.NOTICE_DISMISS).performScrollTo().assertTouchSizeIsAtLeast(48.dp).performClick()

        assertEquals(listOf<PaletteIntent>(PaletteIntent.DismissNotice), harness.intents)

        harness.state.value = PaletteState()
        assertEmptySlot(PALETTE_TAG)
    }

    @Test
    fun `a failed command shows while closed too, with a dismiss and no retry`() {
        val harness = compose.setPalette(PaletteState(error = PaletteError(PaletteAction.RunCommand, ChatFailure.ServerFailed)))

        compose.onNodeWithTag(PaletteTags.TITLE).assertDoesNotExist()
        compose.onNodeWithText("The command could not be run. The server ran into an error.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithContentDescription("Dismiss error").performScrollTo().performClick()

        assertEquals(listOf<PaletteIntent>(PaletteIntent.DismissError), harness.intents)

        harness.state.value = PaletteState()
        assertEmptySlot(PALETTE_TAG)
    }

    @Test
    fun `a closed palette shows both a notice and a failure`() {
        compose.setPalette(
            PaletteState(
                notice = BuiltInNotice.Unavailable,
                error = PaletteError(PaletteAction.RunCommand, ChatFailure.Rejected),
            ),
        )

        compose.onNodeWithTag(PaletteTags.NOTICE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The command could not be run. The server refused the request.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `closing sends Dismiss`() {
        val harness = compose.setPalette(AT)

        compose.onNodeWithTag(PaletteTags.CLOSE).performScrollTo().performClick()

        assertEquals(listOf<PaletteIntent>(PaletteIntent.Dismiss), harness.intents)
    }

    @Test
    fun `rows and the close button are big enough to tap`() {
        compose.setPalette(AT)

        compose.onNodeWithTag(PaletteTags.CLOSE).performScrollTo().assertTouchSizeIsAtLeast(48.dp)
        AT.items.forEach { compose.onNodeWithTag(PaletteTags.item(it)).performScrollTo().assertTouchSizeIsAtLeast(48.dp) }
    }

    @Test
    fun `rows cannot be picked while a command runs`() {
        val harness = compose.setPalette(SLASH.copy(running = true))

        compose.onNodeWithTag(PaletteTags.item(INIT)).performScrollTo().assertIsNotEnabled().performClick()
        assertTrue(harness.intents.isEmpty())

        harness.state.value = SLASH
        compose.onNodeWithTag(PaletteTags.item(INIT)).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `items found while still loading are shown and the loading line too`() {
        compose.setPalette(AT.copy(loading = true))

        compose.onNodeWithTag(PaletteTags.item(SOURCE)).performScrollTo().assertHasClickAction()
        compose.onNodeWithText("Loading…").performScrollTo()
        compose.onNodeWithText("Nothing matches").assertDoesNotExist()
    }

    @Test
    fun `a failed list offers a retry that sends Retry, and a dismiss that sends DismissError`() {
        val harness = compose.setPalette(SLASH.copy(error = PaletteError(PaletteAction.Load, ChatFailure.Unreachable)))

        compose.onNodeWithText("Try again").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Dismiss error").performScrollTo().performClick()

        assertEquals(listOf(PaletteIntent.Retry, PaletteIntent.DismissError), harness.intents)
    }

    @Test
    fun `a failed command offers no retry, only a dismiss, and keeps the rows`() {
        val harness = compose.setPalette(SLASH.copy(error = PaletteError(PaletteAction.RunCommand, ChatFailure.Rejected)))

        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithTag(PaletteTags.item(INIT)).performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription("Dismiss error").performScrollTo().performClick()

        assertEquals(listOf<PaletteIntent>(PaletteIntent.DismissError), harness.intents)
    }

    @Test
    fun `a long list keeps to a bounded height and scrolls to its last row`() {
        val files = (1..60).map { PaletteItem.File("src/file$it.kt") }
        val harness = compose.setPalette(PaletteState(trigger = AT.trigger, items = files))

        val height = compose.onNodeWithTag(PALETTE_TAG).fetchSemanticsNode().let { node ->
            with(node.layoutInfo.density) { node.size.height.toDp() }
        }
        assertTrue(height <= 400.dp, "the palette is $height high")

        compose.onNodeWithTag(PaletteTags.item(files.last())).performScrollTo().performClick()
        assertEquals(listOf<PaletteIntent>(PaletteIntent.Pick(files.last())), harness.intents)
    }

    @Test
    fun `items with the same text but another kind get their own row`() {
        val agent = PaletteItem.Agent(id = "review", name = "review")
        val skill = PaletteItem.Skill("review")
        compose.setPalette(PaletteState(trigger = SLASH.trigger, items = listOf(REVIEW, skill, agent)))

        listOf(REVIEW, skill, agent).forEach { compose.onNodeWithTag(PaletteTags.item(it)).performScrollTo() }
        assertEquals(3, setOf(PaletteTags.item(REVIEW), PaletteTags.item(skill), PaletteTags.item(agent)).size)
    }

    // The chips

    @Test
    fun `no attachments draw nothing, keeping only the empty slot`() {
        val harness = compose.setChips(emptyList())
        assertEmptySlot(CHIPS_TAG)

        harness.attachments.value = ATTACHMENTS
        compose.onNodeWithTag(PaletteTags.chip(FILE_ATTACHMENT)).performScrollTo()
    }

    @Test
    fun `removing a chip sends that attachment and nothing else`() {
        val harness = compose.setChips(ATTACHMENTS)

        compose.onNodeWithTag(PaletteTags.chipRemove(AGENT_ATTACHMENT)).performScrollTo().performClick()

        assertEquals(listOf<PromptAttachment>(AGENT_ATTACHMENT), harness.detached)
    }

    @Test
    fun `each remove button is big enough to tap`() {
        compose.setChips(ATTACHMENTS)

        ATTACHMENTS.forEach {
            compose.onNodeWithTag(PaletteTags.chipRemove(it)).performScrollTo().assertTouchSizeIsAtLeast(48.dp)
        }
    }

    @Test
    fun `many chips scroll sideways to the last one`() {
        val many = (1..30).map { PromptAttachment.File("src/very/long/folder/name/file$it.kt") }
        val harness = compose.setChips(many)

        compose.onNodeWithTag(PaletteTags.chipRemove(many.last())).performScrollTo().performClick()

        assertEquals(listOf<PromptAttachment>(many.last()), harness.detached)
    }

    private fun assertEmptySlot(tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        assertNull(node.config.getOrNull(SemanticsProperties.Text))
        assertTrue(node.children.isEmpty())
        assertEquals(0, node.size.height)
    }
}
