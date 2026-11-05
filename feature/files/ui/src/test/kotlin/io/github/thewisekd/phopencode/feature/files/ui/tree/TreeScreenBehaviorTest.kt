package io.github.thewisekd.phopencode.feature.files.ui.tree

import android.text.format.Formatter
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeSort
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

private val en = ENGLISH_TREE_STRINGS

/** Which intents the tree's controls send, and what each state enables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class TreeScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val intents = mutableListOf<TreeIntent>()

    private fun show(state: TreeState) = compose.setTreeScreen(state) { intents += it }

    private fun segment(name: String) =
        compose.onNode(hasText(name) and hasTestTag(TreeTags.PATH_SEGMENT) and hasAnyAncestor(hasTestTag(TreeTags.PATH_BAR)))

    @Test
    fun `the root keeps the placeholder tag`() {
        show(listing)

        compose.onNodeWithTag("placeholder:${FilesRoute.Tree(ROOT)}").assertExists()
    }

    @Test
    fun `tapping a folder or a file opens it`() {
        show(listing)

        compose.onNodeWithText(docsFolder.entry.name).performScrollTo().performClick()
        compose.onNodeWithText(readme.entry.name).performScrollTo().performClick()

        assertEquals(listOf<TreeIntent>(TreeIntent.Open(docsFolder), TreeIntent.Open(readme)), intents)
    }

    @Test
    fun `a long press shows the entry's actions`() {
        show(listing)

        compose.onNodeWithText(readme.entry.name).performScrollTo().performTouchInput { longClick() }

        assertEquals(listOf<TreeIntent>(TreeIntent.ShowActions(readme)), intents)
    }

    @Test
    fun `a row's action button shows the entry's actions`() {
        show(listing)

        compose.onNodeWithContentDescription(en.actionsFor(link.entry.name)).performScrollTo().performClick()

        assertEquals(listOf<TreeIntent>(TreeIntent.ShowActions(link)), intents)
    }

    @Test
    fun `up is disabled at the project's folder`() {
        show(listing)

        compose.onNodeWithContentDescription(en.up).assertIsNotEnabled()
    }

    @Test
    fun `up is enabled below the project's folder and sends Up`() {
        show(listing.copy(folder = MAIN))

        compose.onNodeWithContentDescription(en.up).assertIsEnabled().performClick()

        assertEquals(listOf<TreeIntent>(TreeIntent.Up), intents)
    }

    @Test
    fun `the path bar opens the folders above the one shown`() {
        show(listing.copy(folder = MAIN))

        segment(ROOT_NAME).performScrollTo().performClick()
        segment("src").performScrollTo().performClick()

        assertEquals(listOf<TreeIntent>(TreeIntent.OpenFolder(ROOT), TreeIntent.OpenFolder(SRC)), intents)
    }

    @Test
    fun `the folder shown is not a button`() {
        show(listing.copy(folder = MAIN))

        segment("main").performScrollTo().assertIsNotEnabled().performClick()

        assertEquals(emptyList(), intents)
    }

    @Test
    fun `system back goes up a folder below the project's folder`() {
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            TreeContent(listing.copy(folder = SRC)) { intents += it }
        }

        compose.runOnIdle { dispatcher!!.onBackPressed() }

        assertEquals(listOf<TreeIntent>(TreeIntent.Up), intents)
    }

    @Test
    fun `system back is left to the app at the project's folder`() {
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            TreeContent(listing) { intents += it }
        }

        compose.runOnIdle { assertEquals(false, dispatcher!!.hasEnabledCallbacks()) }
        assertEquals(emptyList(), intents)
    }

    @Test
    fun `the top bar's back closes the tree`() {
        show(listing.copy(folder = SRC))

        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf<TreeIntent>(TreeIntent.Close), intents)
    }

    @Test
    fun `search and transfers send their intents`() {
        show(listing)

        compose.onNodeWithContentDescription(en.search).performClick()
        compose.onNodeWithContentDescription(en.transfers).performClick()

        assertEquals(listOf<TreeIntent>(TreeIntent.OpenSearch, TreeIntent.OpenTransfer), intents)
    }

    @Test
    fun `new folder sends NewFolder and closes the options`() {
        show(listing)

        compose.onNodeWithContentDescription(en.more).performClick()
        compose.onNodeWithTag(TreeTags.NEW_FOLDER).performScrollTo().performClick()

        assertEquals(listOf<TreeIntent>(TreeIntent.NewFolder), intents)
        compose.onNodeWithTag(TreeTags.OPTIONS).assertDoesNotExist()
    }

    @Test
    fun `refresh sends Refresh and closes the options`() {
        show(listing)

        compose.onNodeWithContentDescription(en.more).performClick()
        compose.onNodeWithTag(TreeTags.REFRESH).performScrollTo().performClick()

        assertEquals(listOf<TreeIntent>(TreeIntent.Refresh), intents)
        compose.onNodeWithTag(TreeTags.OPTIONS).assertDoesNotExist()
    }

    @Test
    fun `refresh is disabled while a folder loads`() {
        show(listing.copy(refreshing = true))

        compose.onNodeWithContentDescription(en.more).performClick()

        compose.onNodeWithTag(TreeTags.REFRESH).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `the hidden files switch shows the state and sends its opposite`() {
        show(listing)

        compose.onNodeWithContentDescription(en.more).performClick()
        compose.onNodeWithTag(TreeTags.SHOW_HIDDEN).performScrollTo().assertIsOff().performClick()

        assertEquals(listOf<TreeIntent>(TreeIntent.SetShowHidden(true)), intents)
    }

    @Test
    fun `hidden files shown can be hidden again`() {
        show(listing.copy(showHidden = true))

        compose.onNodeWithContentDescription(en.more).performClick()
        compose.onNodeWithTag(TreeTags.SHOW_HIDDEN).performScrollTo().assertIsOn().performClick()

        assertEquals(listOf<TreeIntent>(TreeIntent.SetShowHidden(false)), intents)
    }

    @Test
    fun `the sort in use is selected and another one is sent`() {
        show(listing.copy(sort = TreeSort.Modified))

        compose.onNodeWithContentDescription(en.more).performClick()
        compose.onNodeWithTag(TreeTags.SORT_NAME).performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag(TreeTags.SORT_MODIFIED).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(TreeTags.SORT_SIZE).performScrollTo().assertIsNotSelected().performClick()

        assertEquals(listOf<TreeIntent>(TreeIntent.SortBy(TreeSort.Size)), intents)
    }

    @Test
    fun `close shuts the options without an intent`() {
        show(listing)

        compose.onNodeWithContentDescription(en.more).performClick()
        compose.onNodeWithTag(TreeTags.OPTIONS_CLOSE).performScrollTo().performClick()

        compose.onNodeWithTag(TreeTags.OPTIONS).assertDoesNotExist()
        assertEquals(emptyList(), intents)
    }

    @Test
    fun `show hidden on an empty folder sends SetShowHidden`() {
        show(TreeState(directory = ROOT, loading = false))

        compose.onNodeWithText(en.showHiddenAction).performScrollTo().performClick()

        assertEquals(listOf<TreeIntent>(TreeIntent.SetShowHidden(true)), intents)
    }

    @Test
    fun `retry sends Retry`() {
        show(TreeState(directory = ROOT, loading = false, error = FilesFailure.Unreachable))

        // The banner sits above the list, out of any scroller, so it stays in view.
        compose.onNodeWithText(en.retry).performClick()

        assertEquals(listOf<TreeIntent>(TreeIntent.Retry), intents)
    }

    @Test
    fun `an error keeps the rows still shown`() {
        show(listing.copy(error = FilesFailure.Unreachable))

        compose.onNodeWithTag(TreeTags.ERROR).assertExists()
        compose.onNodeWithText(readme.entry.name).performScrollTo()
    }

    @Test
    fun `a first load shows no empty message`() {
        show(TreeState(directory = ROOT))

        compose.onNodeWithTag(TreeTags.LOADING).assertExists()
        compose.onNodeWithText(en.emptyTitle).assertDoesNotExist()
    }

    @Test
    fun `a file's size is shown in the phone's format`() {
        var size = ""
        compose.setContent {
            size = Formatter.formatShortFileSize(LocalContext.current, 2048)
            TreeContent(listing) { intents += it }
        }

        compose.onNodeWithText(size, useUnmergedTree = true).assertExists()
    }
}
