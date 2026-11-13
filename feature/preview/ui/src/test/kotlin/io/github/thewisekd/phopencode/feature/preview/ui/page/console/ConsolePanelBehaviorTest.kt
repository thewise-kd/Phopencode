package io.github.thewisekd.phopencode.feature.preview.ui.page.console

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsAtLeast
import androidx.compose.ui.test.assertTouchWidthIsAtLeast
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeStatusColors
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleEntry
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleKind
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.ui.ports.LARGE_FONT_SCALE
import io.github.thewisekd.phopencode.feature.preview.ui.ports.assertAccessible
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Upward swipes enough to bring a list of about 60 short entries back to its end from anywhere. */
private const val SWIPES_TO_THE_END = 20

/**
 * What the console panel and its toolbar button do; the texts are checked in
 * [ConsoleLanguageContract]. Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ConsolePanelBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<PageIntent>()

    private val en = ENGLISH_CONSOLE_STRINGS

    @Test
    fun `the button opens the panel and the hide button folds it away`() {
        compose.setConsole(allLevels, expanded = false) { sent += it }
        compose.onNodeWithTag(ConsoleTags.PANEL).assertDoesNotExist()

        compose.onNodeWithTag(ConsoleTags.BUTTON).performClick()
        compose.onNodeWithTag(ConsoleTags.PANEL).assertIsDisplayed()

        compose.onNodeWithTag(ConsoleTags.HIDE).performClick()
        compose.onNodeWithTag(ConsoleTags.PANEL).assertDoesNotExist()

        compose.onNodeWithTag(ConsoleTags.BUTTON).performClick()
        compose.onNodeWithTag(ConsoleTags.BUTTON).performClick()
        compose.onNodeWithTag(ConsoleTags.PANEL).assertDoesNotExist()
        assertEquals(emptyList(), sent, "opening and folding the panel is the screen's own business")
    }

    @Test
    fun `the panel lies over the bottom of the page and leaves its top free`() {
        compose.setConsole(allLevels)

        val page = compose.onNodeWithTag(HARNESS_ROOT).fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag(ConsoleTags.PANEL).fetchSemanticsNode().boundsInRoot
        assertEquals(page.bottom, panel.bottom, 0.5f)
        assertEquals(page.left, panel.left, 0.5f)
        assertEquals(page.right, panel.right, 0.5f)
        assertTrue(panel.top > page.top + page.height / 3, "the panel covers only the lower part of the page")
    }

    @Test
    fun `the badge shows the error count, whatever the filter`() {
        val states = ConsoleStates(compose) { sent += it }

        states.show(allLevels)
        compose.onNodeWithTag(ConsoleTags.BADGE, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()

        states.show(allLevels.copy(filter = ConsoleLevel.Log))
        compose.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()

        states.show(ConsoleState(entries = listOf(logEntry, warningEntry)))
        compose.onNodeWithTag(ConsoleTags.BADGE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a large error count is capped in the badge but read in full`() {
        val many = ConsoleState(entries = List(150) { secondError })
        compose.setConsole(many, expanded = false)

        compose.onNodeWithText("99+", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(ConsoleTags.BUTTON).assertContentDescriptionContains("150", substring = true)
    }

    @Test
    fun `each level filter sends its level and all sends none`() {
        compose.setConsole(allLevels) { sent += it }

        ConsoleLevel.entries.forEach { level ->
            compose.onNodeWithTag(ConsoleTags.filter(level)).performClick()
        }
        compose.onNodeWithTag(ConsoleTags.filter(null)).performClick()

        assertEquals(ConsoleLevel.entries.map { PageIntent.FilterConsole(it) } + PageIntent.FilterConsole(null), sent)
    }

    @Test
    fun `the chosen filter is selected and only its entries are listed`() {
        compose.setConsole(allLevels.copy(filter = ConsoleLevel.Error))

        compose.onNodeWithTag(ConsoleTags.filter(ConsoleLevel.Error)).assertIsSelected()
        compose.onNodeWithTag(ConsoleTags.filter(null)).assertIsNotSelected()
        compose.scrollConsoleTo(0)
        compose.onNodeWithText(errorEntry.text, useUnmergedTree = true).assertIsDisplayed()
        compose.scrollConsoleTo(1)
        compose.onNodeWithText(secondError.text, useUnmergedTree = true).assertIsDisplayed()
        listOf(debugEntry, logEntry, infoEntry, warningEntry).forEach {
            compose.onNodeWithText(it.text, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun `clear sends clear, and is off while there is nothing to clear`() {
        val states = ConsoleStates(compose) { sent += it }

        states.show(allLevels)
        compose.onNodeWithTag(ConsoleTags.CLEAR).assertIsEnabled().performClick()
        assertEquals(listOf<PageIntent>(PageIntent.ClearConsole), sent)

        states.show(emptyConsole)
        compose.onNodeWithTag(ConsoleTags.CLEAR).assertIsNotEnabled()
    }

    @Test
    fun `every control is a 48 dp target`() {
        compose.setConsole(allLevels)

        val tags = listOf(ConsoleTags.BUTTON, ConsoleTags.HIDE, ConsoleTags.CLEAR, ConsoleTags.filter(null)) +
            ConsoleLevel.entries.map { ConsoleTags.filter(it) }
        tags.forEach { tag ->
            compose.onNodeWithTag(tag)
                .assertTouchWidthIsAtLeast(48.dp)
                .assertTouchHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun `the panel passes the accessibility audit at the largest font`() {
        compose.setConsoleAt(LARGE_FONT_SCALE, allLevels)

        compose.assertAccessible()
    }

    @Test
    fun `the panel sends nothing but its filter and clear, and never the page's text`() {
        compose.setConsole(allLevels) { sent += it }

        compose.onNodeWithTag(ConsoleTags.CLEAR).performClick()
        ConsoleLevel.entries.forEach { compose.onNodeWithTag(ConsoleTags.filter(it)).performClick() }
        allLevels.entries.indices.forEach {
            compose.scrollConsoleTo(it)
            compose.onNodeWithTag(ConsoleTags.entry(it)).performClick()
        }
        compose.onNodeWithTag(ConsoleTags.HIDE).performClick()

        assertTrue(sent.isNotEmpty())
        assertTrue(
            sent.all { it is PageIntent.FilterConsole || it == PageIntent.ClearConsole },
            "the panel sent something else: $sent",
        )
    }

    @Test
    fun `an entry's text can be selected`() {
        compose.setConsole(allLevels)
        compose.scrollConsoleTo(allLevels.entries.indexOf(logEntry))
        assertEquals(1, compose.onAllNodes(isRoot()).fetchSemanticsNodes().size)

        compose.onNodeWithText(logEntry.text, useUnmergedTree = true).performTouchInput { longClick() }
        compose.waitForIdle()

        assertTrue(
            compose.onAllNodes(isRoot()).fetchSemanticsNodes().size > 1,
            "a long press shows no selection handles or menu",
        )
    }

    @Test
    fun `the newest entry is in view when the console is full`() {
        val full = ConsoleState(
            entries = List(ConsoleState.MAX_ENTRIES) { ConsoleEntry(ConsoleKind.Console, ConsoleLevel.Log, "message $it", null, null) },
        )
        compose.setConsole(full)

        compose.onNodeWithText("message ${ConsoleState.MAX_ENTRIES - 1}", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a user scrolling back stops the list following new entries until they return to the end`() {
        fun logOf(count: Int) = ConsoleState(
            entries = List(count) { ConsoleEntry(ConsoleKind.Console, ConsoleLevel.Log, "message $it", null, null) },
        )
        fun shown(text: String) =
            runCatching { compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed() }.isSuccess
        val states = ConsoleStates(compose)

        states.show(logOf(60))
        assertTrue(shown("message 59"), "the list starts at the newest entry")

        // A fling back up: a new entry while it runs or after it must not pull the list down again.
        compose.onNodeWithTag(ConsoleTags.LIST).performTouchInput { swipeDown() }
        states.show(logOf(61))
        compose.waitForIdle()
        assertFalse(shown("message 60"), "the list jumped back to the end while the user read further up")

        repeat(SWIPES_TO_THE_END) { compose.onNodeWithTag(ConsoleTags.LIST).performTouchInput { swipeUp() } }
        compose.waitForIdle()
        states.show(logOf(62))
        compose.waitForIdle()
        assertTrue(shown("message 61"), "back at the end, the list follows again")
    }

    @Test
    fun `scrolling back with an accessibility action stops the following too, until the end is back in view`() {
        fun logOf(count: Int) = ConsoleState(
            entries = List(count) { ConsoleEntry(ConsoleKind.Console, ConsoleLevel.Log, "message $it", null, null) },
        )
        fun shown(text: String) =
            runCatching { compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed() }.isSuccess
        val states = ConsoleStates(compose)
        states.show(logOf(60))

        // What TalkBack's scroll actions do: no finger, no drag.
        compose.onNodeWithTag(ConsoleTags.LIST).performScrollToIndex(10)
        compose.waitForIdle()
        states.show(logOf(61))
        compose.waitForIdle()
        assertTrue(shown("message 10"), "the list left the entry the user scrolled to")
        assertFalse(shown("message 60"), "the list jumped back to the end")

        compose.onNodeWithTag(ConsoleTags.LIST).performScrollToIndex(60)
        compose.waitForIdle()
        states.show(logOf(62))
        compose.waitForIdle()
        assertTrue(shown("message 61"), "back at the end, the list follows again")
    }

    @Test
    fun `an entry taller than the panel does not stop the following`() {
        fun log(text: String) = ConsoleEntry(ConsoleKind.Console, ConsoleLevel.Log, text, null, null)
        val start = List(5) { log("message $it") }
        // The longest text the console keeps, wrapped into far more lines than the panel shows.
        val tall = log("word ".repeat(ConsoleState.MAX_TEXT_LENGTH / 5))
        val states = ConsoleStates(compose)

        states.show(ConsoleState(entries = start))
        states.show(ConsoleState(entries = start + tall))
        compose.waitForIdle()
        states.show(ConsoleState(entries = start + tall + log("after the tall one")))
        compose.waitForIdle()

        compose.onNodeWithText("after the tall one", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `each level has its own icon, and errors, warnings and info take the status colours`() {
        var status: PhopencodeStatusColors? = null
        var styles: Map<ConsoleLevel, LevelStyle> = emptyMap()
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                status = PhopencodeTheme.status
                styles = ConsoleLevel.entries.associateWith { it.style() }
            }
        }
        compose.waitForIdle()
        val colors = assertNotNull(status)

        assertEquals(ConsoleLevel.entries.size, ConsoleLevel.entries.map { it.icon }.toSet().size)
        assertEquals(PhopencodeIcons.Warning, ConsoleLevel.Warning.icon)
        assertEquals(PhopencodeIcons.Info, ConsoleLevel.Info.icon)
        assertEquals(colors.danger.container, styles.getValue(ConsoleLevel.Error).container)
        assertEquals(colors.danger.onContainer, styles.getValue(ConsoleLevel.Error).icon)
        assertEquals(colors.warning.container, styles.getValue(ConsoleLevel.Warning).container)
        assertEquals(colors.warning.onContainer, styles.getValue(ConsoleLevel.Warning).icon)
        assertEquals(colors.info.color, styles.getValue(ConsoleLevel.Info).icon)
        assertEquals(colors.danger.color, styles.getValue(ConsoleLevel.Error).accent)
        assertEquals(colors.warning.color, styles.getValue(ConsoleLevel.Warning).accent)
        assertNotEquals(styles.getValue(ConsoleLevel.Log).content, styles.getValue(ConsoleLevel.Debug).content)
    }
}
