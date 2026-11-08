package io.github.thewisekd.phopencode.feature.settings.ui.prefs

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import io.github.thewisekd.phopencode.feature.settings.logic.contract.DataDeletion
import io.github.thewisekd.phopencode.feature.settings.logic.contract.FileSizeProblem
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesError
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesIntent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The screen sends the contract's intents and decides nothing: a tap on a language sends it at once
 * (the presenter stores and applies it), and deleting asks first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class PreferencesBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private val selectableGroup = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)

    @Test
    fun `the current language and theme are selected`() {
        compose.showPreferences(loadedState)

        compose.onNodeWithTag(languageOptionTag(AppLanguage.ENGLISH)).assertIsSelected()
        compose.onNodeWithTag(languageOptionTag(AppLanguage.TURKISH)).assertIsNotSelected()
        compose.onNodeWithTag(languageOptionTag(AppLanguage.SYSTEM)).assertIsNotSelected()
        compose.onNodeWithTag(themeOptionTag(ThemePreference.DARK)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(themeOptionTag(ThemePreference.LIGHT)).assertIsNotSelected()
        compose.onNodeWithTag(themeOptionTag(ThemePreference.SYSTEM)).assertIsNotSelected()
    }

    @Test
    fun `the options are radio buttons in groups with full touch targets`() {
        compose.showPreferences(loadedState)

        compose.onNodeWithTag(LANGUAGE_GROUP_TAG).assert(selectableGroup)
        compose.onNodeWithTag(THEME_GROUP_TAG).assert(selectableGroup)
        for (language in AppLanguage.entries) {
            compose.onNodeWithTag(languageOptionTag(language)).performScrollTo()
                .assert(radio).assertIsEnabled().assertTouchSizeIsAtLeast()
        }
        for (theme in ThemePreference.entries) {
            compose.onNodeWithTag(themeOptionTag(theme)).performScrollTo()
                .assert(radio).assertIsEnabled().assertTouchSizeIsAtLeast()
        }
        compose.onNodeWithTag(LICENSES_TAG).performScrollTo().assertTouchSizeIsAtLeast()
        compose.onNodeWithTag(DELETE_BUTTON_TAG).performScrollTo().assertTouchSizeIsAtLeast()
    }

    @Test
    fun `tapping a language sends it at once`() {
        val sent = compose.showPreferences(loadedState)

        compose.onNodeWithTag(languageOptionTag(AppLanguage.TURKISH)).performClick()
        compose.onNodeWithTag(languageOptionTag(AppLanguage.SYSTEM)).performClick()

        assertEquals(
            listOf<PreferencesIntent>(
                PreferencesIntent.SetLanguage(AppLanguage.TURKISH),
                PreferencesIntent.SetLanguage(AppLanguage.SYSTEM),
            ),
            sent,
        )
    }

    @Test
    fun `tapping a theme sends it`() {
        val sent = compose.showPreferences(loadedState)

        compose.onNodeWithTag(themeOptionTag(ThemePreference.LIGHT)).performScrollTo().performClick()

        assertEquals(listOf<PreferencesIntent>(PreferencesIntent.SetTheme(ThemePreference.LIGHT)), sent)
    }

    @Test
    fun `typing a limit sends the text`() {
        val sent = compose.showPreferences(loadedState)

        compose.onNodeWithTag(FILE_SIZE_FIELD_TAG).performScrollTo().performTextReplacement("50")

        assertEquals(listOf<PreferencesIntent>(PreferencesIntent.EditFileSizeLimit("50")), sent)
    }

    @Test
    fun `a limit problem marks the field as wrong`() {
        compose.showPreferences(loadedState.copy(fileSizeLimitText = "0", fileSizeProblem = FileSizeProblem.TooSmall))

        compose.onNodeWithTag(FILE_SIZE_FIELD_TAG).performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test
    fun `a valid limit is not marked`() {
        compose.showPreferences(loadedState)

        compose.onNodeWithTag(FILE_SIZE_FIELD_TAG).performScrollTo()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }

    @Test
    fun `the licenses entry opens the licenses`() {
        val sent = compose.showPreferences(loadedState)

        compose.onNodeWithTag(LICENSES_TAG).performScrollTo().performClick()

        assertEquals(listOf<PreferencesIntent>(PreferencesIntent.OpenLicenses), sent)
    }

    @Test
    fun `back closes the screen`() {
        val sent = compose.showPreferences(loadedState)

        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf<PreferencesIntent>(PreferencesIntent.Close), sent)
    }

    @Test
    fun `the delete button only asks for confirmation`() {
        val sent = compose.showPreferences(loadedState)

        compose.onNodeWithTag(DELETE_DIALOG_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DELETE_BUTTON_TAG).performScrollTo().performClick()

        assertEquals(listOf<PreferencesIntent>(PreferencesIntent.RequestDelete), sent)
        compose.onNodeWithTag(DELETE_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun `confirming deletes`() {
        val sent = compose.showPreferences(loadedState.copy(deletion = DataDeletion.Confirming))

        compose.onNodeWithTag(DELETE_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("Dangerous action").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()

        assertEquals(listOf<PreferencesIntent>(PreferencesIntent.ConfirmDelete), sent)
    }

    @Test
    fun `cancelling keeps the data`() {
        val sent = compose.showPreferences(loadedState.copy(deletion = DataDeletion.Confirming))

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(listOf<PreferencesIntent>(PreferencesIntent.CancelDelete), sent)
    }

    @Test
    fun `while deleting nothing can be changed`() {
        compose.showPreferences(loadedState.copy(deletion = DataDeletion.Deleting))

        for (language in AppLanguage.entries) {
            compose.onNodeWithTag(languageOptionTag(language)).assertIsNotEnabled()
        }
        for (theme in ThemePreference.entries) {
            compose.onNodeWithTag(themeOptionTag(theme)).performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithTag(FILE_SIZE_FIELD_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(DELETE_BUTTON_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DELETE_DIALOG_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DELETING_TAG).performScrollTo()
            .assert(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
    }

    @Test
    fun `an error can be dismissed`() {
        val sent = compose.showPreferences(loadedState.copy(error = PreferencesError.SaveFailed))

        compose.onNodeWithContentDescription("Dismiss error").performClick()

        assertEquals(listOf<PreferencesIntent>(PreferencesIntent.DismissError), sent)
    }

    @Test
    fun `a failed deletion is asked again through the confirmation`() {
        val sent = compose.showPreferences(loadedState.copy(error = PreferencesError.DeleteFailed))

        compose.onNodeWithText("Try again").performClick()

        assertEquals(listOf<PreferencesIntent>(PreferencesIntent.RequestDelete), sent)
    }

    @Test
    fun `loading takes no input`() {
        val sent = compose.showPreferences(PreferencesState())

        compose.onNodeWithTag(languageOptionTag(AppLanguage.TURKISH)).assertDoesNotExist()
        compose.onNodeWithTag(DELETE_BUTTON_TAG).assertDoesNotExist()
        assertEquals(emptyList(), sent)
    }
}
