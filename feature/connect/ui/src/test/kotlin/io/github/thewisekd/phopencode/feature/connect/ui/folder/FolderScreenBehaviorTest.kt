package io.github.thewisekd.phopencode.feature.connect.ui.folder

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

private val en = ENGLISH_FOLDER_STRINGS

/** Which intents the folder picker's controls send, and what each state enables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class FolderScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val intents = mutableListOf<FolderIntent>()

    private fun show(state: FolderState) = compose.setFolderScreen(state) { intents += it }

    @Test
    fun `the root keeps the placeholder tag`() {
        show(browsing)

        compose.onNodeWithTag("placeholder:${ConnectRoute.FolderPicker}").assertExists()
    }

    @Test
    fun `up is enabled below the root and sends Up`() {
        show(browsing)

        compose.onNodeWithContentDescription(en.up).assertIsEnabled().performClick()

        assertEquals(listOf<FolderIntent>(FolderIntent.Up), intents)
    }

    @Test
    fun `up is disabled at the root`() {
        show(browsing.copy(path = "/"))

        compose.onNodeWithContentDescription(en.up).assertIsNotEnabled().performClick()

        assertEquals(emptyList(), intents)
    }

    @Test
    fun `up is disabled before the first listing`() {
        show(FolderState(loading = true))

        compose.onNodeWithContentDescription(en.up).assertIsNotEnabled()
    }

    @Test
    fun `tapping a folder opens it`() {
        show(browsing)

        compose.onNodeWithText(beta.name).performClick()
        compose.onNodeWithText(linked.name).performClick()

        assertEquals(listOf<FolderIntent>(FolderIntent.Open(beta.path), FolderIntent.Open(linked.path)), intents)
    }

    @Test
    fun `select sends the current folder`() {
        show(browsing)

        compose.onNodeWithText(en.select).assertIsEnabled().performClick()

        assertEquals(listOf<FolderIntent>(FolderIntent.Select(CURRENT)), intents)
    }

    @Test
    fun `select is disabled with no folder shown`() {
        show(FolderState(loading = false, error = FolderError.NotConnected))

        compose.onNodeWithText(en.select).assertIsNotEnabled()
    }

    @Test
    fun `select is disabled while a folder loads`() {
        show(browsing.copy(loading = true))

        compose.onNodeWithText(en.select).assertIsNotEnabled()
    }

    @Test
    fun `the root folder can be selected`() {
        show(FolderState(loading = false, path = "/"))

        compose.onNodeWithText(en.select).performClick()

        assertEquals(listOf<FolderIntent>(FolderIntent.Select("/")), intents)
    }

    @Test
    fun `tapping a recent folder selects it`() {
        show(browsing.copy(recent = listOf(RECENT_ONE, RECENT_TWO)))

        compose.onNodeWithText(RECENT_TWO).performClick()

        assertEquals(listOf<FolderIntent>(FolderIntent.Select(RECENT_TWO)), intents)
    }

    @Test
    fun `remove takes a recent folder off the list`() {
        show(browsing.copy(recent = listOf(RECENT_ONE, RECENT_TWO)))

        compose.onNodeWithContentDescription(en.removeRecent(RECENT_ONE)).performClick()

        assertEquals(listOf<FolderIntent>(FolderIntent.RemoveRecent(RECENT_ONE)), intents)
    }

    @Test
    fun `no recent folders hides the section`() {
        show(browsing)

        compose.onNodeWithText(en.recent).assertDoesNotExist()
    }

    @Test
    fun `only the symlinked folder is marked as a link`() {
        show(browsing)

        compose.onAllNodesWithContentDescription(en.symlink, useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(hasText(linked.name) and hasContentDescription(en.symlink)).assertIsDisplayed()
        compose.onNode(hasText(alpha.name) and hasContentDescription(en.folder)).assertIsDisplayed()
        compose.onNode(hasText(alpha.name) and hasContentDescription(en.symlink)).assertDoesNotExist()
    }

    @Test
    fun `loading shows a still message, not the folders`() {
        show(browsing.copy(loading = true))

        compose.onNodeWithContentDescription(en.loading)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
        compose.onNodeWithText(alpha.name).assertDoesNotExist()
        compose.onNodeWithText(en.emptyTitle).assertDoesNotExist()
    }

    @Test
    fun `an error hides the empty state`() {
        show(FolderState(loading = false, path = CURRENT, error = FolderError.PermissionDenied))

        compose.onNodeWithText(en.emptyTitle).assertDoesNotExist()
    }

    @Test
    fun `retry and dismiss send their intents`() {
        show(browsing.copy(error = FolderError.Failed))

        compose.onNodeWithText(en.retry).performClick()
        compose.onNodeWithContentDescription(en.dismiss).performClick()

        assertEquals(listOf(FolderIntent.Retry, FolderIntent.DismissError), intents)
    }

    @Test
    fun `back closes the picker`() {
        show(browsing)

        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf<FolderIntent>(FolderIntent.Close), intents)
    }

    @Test
    fun `controls are at least 48dp`() {
        show(browsing.copy(recent = listOf(RECENT_ONE)))

        compose.onNodeWithContentDescription(en.up).assertTouchAtLeast()
        compose.onNodeWithText(en.select).assertTouchAtLeast()
        compose.onNodeWithContentDescription(en.removeRecent(RECENT_ONE)).assertTouchAtLeast()
        compose.onNodeWithText(RECENT_ONE).assertTouchAtLeast()
        compose.onNodeWithText(alpha.name).assertTouchAtLeast()
    }
}
