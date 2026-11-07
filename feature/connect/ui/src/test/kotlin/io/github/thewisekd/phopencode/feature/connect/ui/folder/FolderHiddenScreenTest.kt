package io.github.thewisekd.phopencode.feature.connect.ui.folder

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
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

/** A made-up hidden folder. */
private val dotGit = FileEntry(path = "$CURRENT/.git", type = FileEntryType.Directory)

/** The hidden folders switch on the top bar and the screen of a folder whose folders are all hidden (P9.31). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class FolderHiddenScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val intents = mutableListOf<FolderIntent>()

    private fun show(state: FolderState) = compose.setFolderScreen(state) { intents += it }

    @Test
    fun `the switch is a 48dp toggle that asks to show hidden folders`() {
        show(browsing.copy(hiddenCount = 1))

        compose.onNode(hasContentDescription(en.showHidden) and isToggleable())
            .assertIsDisplayed()
            .assertTouchAtLeast()
            .performClick()

        assertEquals(listOf<FolderIntent>(FolderIntent.SetShowHidden(true)), intents)
    }

    @Test
    fun `while on, the switch asks to hide them`() {
        show(browsing.copy(entries = listOf(dotGit) + browsing.entries, showHidden = true))

        compose.onNodeWithText(dotGit.name).assertIsDisplayed()
        compose.onNodeWithContentDescription(en.showHidden).performClick()

        assertEquals(listOf<FolderIntent>(FolderIntent.SetShowHidden(false)), intents)
    }

    @Test
    fun `the switch works while a folder loads or after an error`() {
        show(FolderState(loading = true, error = FolderError.NotConnected))

        compose.onNodeWithContentDescription(en.showHidden).performClick()

        assertEquals(listOf<FolderIntent>(FolderIntent.SetShowHidden(true)), intents)
    }

    @Test
    fun `the all hidden screen's action shows them`() {
        show(FolderState(loading = false, path = CURRENT, hiddenCount = 3))

        compose.onNodeWithText(en.showHidden).assertTouchAtLeast().performClick()

        assertEquals(listOf<FolderIntent>(FolderIntent.SetShowHidden(true)), intents)
    }

    @Test
    fun `a folder with no folders at all keeps the plain empty state`() {
        show(FolderState(loading = false, path = CURRENT))

        compose.onNodeWithText(en.emptyTitle).assertIsDisplayed()
        compose.onNodeWithText(en.allHiddenTitle).assertDoesNotExist()
    }

    @Test
    fun `some hidden folders do not replace the visible ones`() {
        show(browsing.copy(hiddenCount = 2))

        compose.onNodeWithText(alpha.name).assertIsDisplayed()
        compose.onNodeWithText(en.allHiddenTitle).assertDoesNotExist()
        compose.onNodeWithText(en.emptyTitle).assertDoesNotExist()
    }

    @Test
    fun `an error hides the all hidden screen`() {
        show(FolderState(loading = false, path = CURRENT, hiddenCount = 2, error = FolderError.PermissionDenied))

        compose.onNodeWithText(en.allHiddenTitle).assertDoesNotExist()
    }
}
