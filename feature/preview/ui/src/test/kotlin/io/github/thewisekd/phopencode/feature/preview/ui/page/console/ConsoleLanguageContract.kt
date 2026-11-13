package io.github.thewisekd.phopencode.feature.preview.ui.page.console

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleEntry
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the console panel in one language. */
internal data class ExpectedConsoleStrings(
    val title: String,
    val show: String,
    val hide: String,
    /** The button's description with [show] or [hide] and an error count above zero. */
    val withErrors: (action: String, count: Int) -> String,
    val clear: String,
    val all: String,
    val levels: Map<ConsoleLevel, String>,
    /** What TalkBack reads for an entry: its level, its text and, if any, where it came from. */
    val entry: (level: String, text: String, location: String?) -> String,
    val sourceLine: (source: String, line: Int) -> String,
    val line: (Int) -> String,
    val empty: String,
    val emptyFiltered: String,
)

/** What the console shows and reads out, the same in each language; a subclass sets the language and its texts. */
abstract class ConsoleLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedConsoleStrings

    private fun location(entry: ConsoleEntry): String? {
        val source = entry.source
        val line = entry.line
        return when {
            source != null && line != null -> strings.sourceLine(source, line)
            source != null -> source
            line != null -> strings.line(line)
            else -> null
        }
    }

    @Test
    fun `the collapsed button offers to show the console and says how many errors there are`() {
        compose.setConsole(allLevels, expanded = false)

        compose.onNodeWithTag(ConsoleTags.BUTTON).assertContentDescriptionEquals(strings.withErrors(strings.show, 2))
    }

    @Test
    fun `one error reads in the singular`() {
        compose.setConsole(ConsoleState(entries = listOf(logEntry, errorEntry)), expanded = false)

        compose.onNodeWithTag(ConsoleTags.BUTTON).assertContentDescriptionEquals(strings.withErrors(strings.show, 1))
    }

    @Test
    fun `without errors the button only names its action`() {
        compose.setConsole(ConsoleState(entries = listOf(logEntry)), expanded = true)

        compose.onNodeWithTag(ConsoleTags.BUTTON).assertContentDescriptionEquals(strings.hide)
    }

    @Test
    fun `the panel names itself, its clear button and every level filter`() {
        compose.setConsole(allLevels)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithText(strings.clear).assertIsDisplayed()
        compose.onNodeWithTag(ConsoleTags.HIDE).assertContentDescriptionEquals(strings.hide)
        compose.onNodeWithTag(ConsoleTags.filter(null)).assertIsDisplayed()
        compose.onNodeWithText(strings.all, useUnmergedTree = true).assertExists()
        assertEquals(ConsoleLevel.entries.toSet(), strings.levels.keys)
        assertEquals(strings.levels.size, strings.levels.values.toSet().size, "level names must differ")
        ConsoleLevel.entries.forEach { level ->
            compose.onNodeWithTag(ConsoleTags.filter(level)).assertExists()
            compose.onNodeWithText(strings.levels.getValue(level), useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun `talkback reads each entry's level, text and origin`() {
        compose.setConsole(allLevels)

        allLevels.entries.forEachIndexed { index, entry ->
            val level = strings.levels.getValue(entry.level)
            compose.scrollConsoleTo(index)
            compose.onNodeWithTag(ConsoleTags.entry(index))
                .assertContentDescriptionEquals(strings.entry(level, entry.text, location(entry)))
        }
    }

    @Test
    fun `each entry shows its text and where it came from`() {
        compose.setConsole(allLevels)

        allLevels.entries.forEachIndexed { index, entry ->
            compose.scrollConsoleTo(index)
            compose.onNodeWithText(entry.text, useUnmergedTree = true).assertIsDisplayed()
            location(entry)?.let { compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed() }
        }
    }

    @Test
    fun `an empty console says so, and so does an empty level`() {
        val states = ConsoleStates(compose)

        states.show(emptyConsole)
        compose.onNodeWithText(strings.empty).assertIsDisplayed()

        states.show(ConsoleState(entries = listOf(logEntry), filter = ConsoleLevel.Error))
        compose.onNodeWithText(strings.emptyFiltered).assertIsDisplayed()
    }
}
