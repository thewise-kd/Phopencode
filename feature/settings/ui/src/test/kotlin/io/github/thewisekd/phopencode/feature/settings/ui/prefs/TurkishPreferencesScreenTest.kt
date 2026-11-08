package io.github.thewisekd.phopencode.feature.settings.ui.prefs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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

/** The screen speaks Turkish on a Turkish phone: a sample of its strings, the parity check covers the rest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishPreferencesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shown(text: String) = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    @Test
    fun `every section is in Turkish`() {
        compose.showPreferences(loadedState)

        compose.onNodeWithText("Ayarlar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Geri dön").assertIsDisplayed()
        shown("Dil")
        compose.onNodeWithTag(languageOptionTag(AppLanguage.SYSTEM)).assertTextContains("Sistem varsayılanı")
        compose.onNodeWithTag(languageOptionTag(AppLanguage.TURKISH)).assertTextContains("Türkçe")
        compose.onNodeWithTag(languageOptionTag(AppLanguage.ENGLISH)).assertTextContains("English")
        shown("Tema")
        compose.onNodeWithTag(themeOptionTag(ThemePreference.SYSTEM)).assertTextContains("Sistem varsayılanı")
        shown("Açık")
        shown("Koyu")
        shown("Dosya boyutu sınırı")
        shown("Açık kaynak lisansları")
        shown("Tüm verileri sil")
    }

    @Test
    fun `a limit problem is in Turkish`() {
        compose.showPreferences(loadedState.copy(fileSizeLimitText = "500", fileSizeProblem = FileSizeProblem.TooLarge))

        shown("Sınır en fazla 100 MiB olabilir.")
    }

    @Test
    fun `loading is in Turkish`() {
        compose.showPreferences(PreferencesState())

        compose.onNodeWithContentDescription("Ayarlar yükleniyor…").assertIsDisplayed()
    }

    @Test
    fun `deleting is in Turkish`() {
        compose.showPreferences(loadedState.copy(deletion = DataDeletion.Deleting))

        compose.onNodeWithContentDescription("Tüm veriler siliniyor…").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the confirmation and errors are in Turkish`() {
        compose.showPreferences(
            loadedState.copy(deletion = DataDeletion.Confirming, error = PreferencesError.DeleteFailed),
        )

        compose.onNodeWithText("Tüm veriler silinsin mi?").assertIsDisplayed()
        compose.onNodeWithText("Sil").assertIsDisplayed()
        compose.onNodeWithText("İptal").assertIsDisplayed()
        compose.onNodeWithText("Verilerin hepsi silinemedi. Bitirmek için yeniden silin.").assertExists()
    }
}
