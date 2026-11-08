package io.github.thewisekd.phopencode.feature.sessions.ui.list

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListIntent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A generous average advance of a mostly lowercase word or path in Roboto, in ems; by Roboto's
 * advance widths, "Sessions", "Oturumlar" and folder names come to roughly 0.5 em a letter.
 */
private const val AVERAGE_ADVANCE_EM = 0.55f

/** The top bar's catalog menu: one icon button whose items open the catalog's screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SessionListCatalogMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = ENGLISH_SESSION_LIST_STRINGS
    private val opened = mutableListOf<CatalogDestination>()
    private val intents = mutableListOf<SessionListIntent>()

    private fun show(directory: String? = null) {
        compose.setSessionList(listed.copy(directory = directory), onOpenCatalog = { opened += it }) { intents += it }
    }

    @Test
    fun `the menu is closed until its button is tapped`() {
        show()

        strings.catalogItems.values.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithContentDescription(strings.catalog).performClick()
        strings.catalogItems.values.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `the menu button is a 48 dp target in the list of every folder`() {
        show()
        compose.onNodeWithContentDescription(strings.catalog).assertIsDisplayed().assertTouchSizeIsAtLeast()
    }

    @Test
    fun `the menu button is there beside a folder's files and git buttons`() {
        show(directory = FOLDER)

        compose.onNodeWithContentDescription(strings.openFiles).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.openGit).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.refresh).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.catalog).assertIsDisplayed().assertTouchSizeIsAtLeast()
    }

    @Test
    fun `skills opens the skills`() = assertItemOpens(CatalogDestination.Skills)

    @Test
    fun `commands, agents and models opens the registry`() = assertItemOpens(CatalogDestination.Registry)

    @Test
    fun `MCP and plugin status opens the status`() = assertItemOpens(CatalogDestination.Status)

    @Test
    fun `each item is a 48 dp target`() {
        show()
        compose.onNodeWithContentDescription(strings.catalog).performClick()

        strings.catalogItems.values.forEach { compose.onNodeWithText(it).assertTouchSizeIsAtLeast() }
    }

    /** A narrow phone with a folder: four buttons in the bar, and room left for the whole title. */
    @Test
    @Config(qualifiers = "en-w320dp-h640dp")
    fun `on a narrow phone the English title has room and every button stays on screen`() =
        assertNarrowBarFits(ENGLISH_SESSION_LIST_STRINGS)

    @Test
    @Config(qualifiers = "tr-w320dp-h640dp")
    fun `on a narrow phone the Turkish title has room and every button stays on screen`() =
        assertNarrowBarFits(TURKISH_SESSION_LIST_STRINGS)

    /**
     * The room the bar leaves the title must hold its letters at [AVERAGE_ADVANCE_EM] each. An
     * estimate: Robolectric's native graphics, which measure with a real font, do not run on arm64
     * Linux, and the legacy mode's text widths mean nothing. The buttons are checked exactly.
     */
    private fun assertNarrowBarFits(language: ExpectedSessionListStrings) {
        show(directory = FOLDER)

        val title = compose.onNodeWithText(language.title).assertIsDisplayed().fetchSemanticsNode()
        assertHasRoom(title, letters = language.title.length, what = "the title")

        val rootWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.right
        val titleRight = title.boundsInRoot.right
        listOf(language.openFiles, language.openGit, language.refresh, language.catalog).forEach { label ->
            val bounds = compose.onNodeWithContentDescription(label).assertIsDisplayed()
                .assertTouchSizeIsAtLeast().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.right <= rootWidth, "$label runs off the screen")
            assertTrue(bounds.left >= titleRight, "$label overlaps the title")
        }
    }

    /**
     * A deep folder on a narrow phone: the subtitle is compacted to at most [FOLDER_SUBTITLE_MAX]
     * characters, and the bar beside four buttons has room for that many, so its last folders are
     * not cut off at the end.
     */
    @Test
    @Config(qualifiers = "en-w320dp-h640dp")
    fun `on a narrow phone a deep folder's subtitle has room beside four buttons`() {
        val deep = "/home/someone/projects/clients/acme/web"
        val subtitle = compactFolder(deep)
        assertEquals("…/acme/web", subtitle)
        show(directory = deep)

        val node = compose.onNodeWithText(subtitle, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        assertHasRoom(node, letters = FOLDER_SUBTITLE_MAX, what = "the subtitle")
    }

    /**
     * The width the bar gives the text of [node] holds [letters] letters at [AVERAGE_ADVANCE_EM]
     * each, at the text's own font size.
     */
    private fun assertHasRoom(node: SemanticsNode, letters: Int, what: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        val room = layout.layoutInput.constraints.maxWidth
        val needed = with(layout.layoutInput.density) {
            letters * AVERAGE_ADVANCE_EM * layout.layoutInput.style.fontSize.toPx()
        }
        assertTrue(room >= needed, "$what gets $room px, needs about $needed px")
    }

    private fun assertItemOpens(destination: CatalogDestination) {
        show()

        compose.onNodeWithContentDescription(strings.catalog).performClick()
        compose.onNodeWithText(strings.catalogItems.getValue(destination)).performClick()

        compose.runOnIdle {
            assertEquals(listOf(destination), opened)
            assertTrue(intents.none { it != SessionListIntent.LoadMore }, "a catalog item sends no intent")
        }
        strings.catalogItems.values.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }
}
