package io.github.thewisekd.phopencode.feature.files.ui.tree

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeEntry
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeIntent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LARGE = 5_000

/** Far more rows than fit on a phone screen, and far fewer than the folder holds. */
private const val MOST_ROWS_COMPOSED = 60

/**
 * A folder of thousands of entries stays smooth: only the rows on screen are composed, every row
 * is keyed by its path, so a reload that adds or removes entries above keeps the rows in view
 * where they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class TreeLargeFolderTest {
    @get:Rule
    val compose = createComposeRule()

    private val intents = mutableListOf<TreeIntent>()

    @Test
    fun `only the rows on screen are composed`() {
        compose.setTreeScreen(largeFolder(LARGE)) { intents += it }

        val composed = compose.onAllNodesWithTag(TreeTags.ROW).fetchSemanticsNodes().size
        assertTrue(composed in 1..MOST_ROWS_COMPOSED, "composed $composed rows of $LARGE")
    }

    @Test
    fun `the last entry can be reached by its key and opened`() {
        compose.setTreeScreen(largeFolder(LARGE)) { intents += it }
        val last = file(LARGE - 1)

        compose.onNodeWithTag(TreeTags.LIST).performScrollToKey(last.entry.path)
        compose.onNodeWithText(last.entry.name).performScrollTo().performClick()

        assertEquals(listOf<TreeIntent>(TreeIntent.Open(last)), intents)
        val composed = compose.onAllNodesWithTag(TreeTags.ROW).fetchSemanticsNodes().size
        assertTrue(composed <= MOST_ROWS_COMPOSED, "composed $composed rows of $LARGE after scrolling")
    }

    @Test
    fun `a reload that adds entries above keeps the rows in view`() {
        var state by mutableStateOf(largeFolder(LARGE))
        compose.setContent { TreeContent(state) { intents += it } }
        val middle = file(LARGE / 2)
        compose.onNodeWithTag(TreeTags.LIST).performScrollToKey(middle.entry.path)
        compose.onNodeWithText(middle.entry.name).assertIsDisplayed()

        val added = List(20) { TreeEntry(FileEntry(path = "$ROOT/aaa-$it", type = FileEntryType.Directory)) }
        compose.runOnIdle { state = state.copy(entries = added + state.entries) }

        compose.onNodeWithText(middle.entry.name).assertIsDisplayed()
    }
}
