package io.github.thewisekd.phopencode.feature.settings.ui.prefs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import io.github.thewisekd.phopencode.feature.settings.logic.contract.DataDeletion
import io.github.thewisekd.phopencode.feature.settings.logic.contract.FileSizeProblem
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesError
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The screen in English: every section, the inline limit problems, the dialog and the errors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishPreferencesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shown(text: String) = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    @Test
    fun `every section is in English`() {
        compose.showPreferences(loadedState)

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Go back").assertIsDisplayed()
        shown("Language")
        compose.onNodeWithTag(languageOptionTag(AppLanguage.SYSTEM)).assertTextContains("System default")
        compose.onNodeWithTag(languageOptionTag(AppLanguage.TURKISH)).assertTextContains("Türkçe")
        compose.onNodeWithTag(languageOptionTag(AppLanguage.ENGLISH)).assertTextContains("English")
        shown("Theme")
        compose.onNodeWithTag(themeOptionTag(ThemePreference.SYSTEM)).assertTextContains("System default")
        shown("Light")
        shown("Dark")
        shown("File size limit")
        shown("Larger files ask before they open. 1 to 100 MiB.")
        shown("Open source licenses")
        shown("Erase every server, key, password and setting on this phone.")
        shown("Delete all data")
    }

    @Test
    fun `loading shows a still message`() {
        compose.showPreferences(PreferencesState())

        compose.onNodeWithTag(PREFS_LOADING_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading settings…").assertIsDisplayed()
    }

    @Test
    fun `each limit problem is shown under the field`() {
        val messages = mapOf(
            FileSizeProblem.Blank to "Enter a size in MiB.",
            FileSizeProblem.NotANumber to "Enter a whole number of MiB.",
            FileSizeProblem.TooSmall to "The limit is at least 1 MiB.",
            FileSizeProblem.TooLarge to "The limit is at most 100 MiB.",
        )
        var problem by mutableStateOf(FileSizeProblem.Blank)
        compose.setThemedContent {
            PreferencesScreen(
                SettingsRoute.Preferences,
                loadedState.copy(fileSizeLimitText = "x", fileSizeProblem = problem),
                onIntent = {},
            )
        }
        for ((next, message) in messages) {
            problem = next
            compose.waitForIdle()
            shown(message)
        }
    }

    @Test
    fun `the confirmation is in English`() {
        compose.showPreferences(loadedState.copy(deletion = DataDeletion.Confirming))

        compose.onNodeWithText("Delete all data?").assertIsDisplayed()
        compose.onNodeWithText(
            "Every server, key, password and setting on this phone is erased and the connection is closed. " +
                "This cannot be undone.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun `deleting shows its progress`() {
        compose.showPreferences(loadedState.copy(deletion = DataDeletion.Deleting))

        compose.onNodeWithTag(DELETING_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Deleting all data…").assertIsDisplayed()
    }

    @Test
    fun `a failed save is in English`() {
        compose.showPreferences(loadedState.copy(error = PreferencesError.SaveFailed))

        shown("The setting could not be saved.")
    }

    @Test
    fun `a failed deletion says how to finish it`() {
        compose.showPreferences(loadedState.copy(error = PreferencesError.DeleteFailed))

        shown("Not all data could be deleted. Delete again to finish.")
    }
}
