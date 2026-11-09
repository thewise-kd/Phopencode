package io.github.thewisekd.phopencode.feature.settings.ui.licenses

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.thewisekd.phopencode.feature.settings.ui.LARGE_FONT_SCALE
import io.github.thewisekd.phopencode.feature.settings.ui.assertAccessible
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The licenses in Turkish: the screen's own words are translated; license names and texts are not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishLicensesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the headings are in Turkish`() {
        compose.showLicenses()

        compose.onNodeWithText("Açık kaynak lisansları").assertIsDisplayed()
        compose.onNodeWithContentDescription("Geri dön").assertIsDisplayed()
        compose.scrolledToText("LGPL bildirimi").assertIsDisplayed()
        compose.scrolledToText("EPL bildirimi").assertIsDisplayed()
        compose.scrolledToText("Kod renklendirme").assertIsDisplayed()
        compose.scrolledToText("Lisans metinleri").assertIsDisplayed()
        compose.scrolledTo(LIBRARIES_HEADING_TAG).assertTextContains("Kütüphaneler")
    }

    @Test
    fun `the LGPL notice is in Turkish`() {
        compose.showLicenses()

        compose.scrolledTo(LGPL_NOTICE_TAG)
            .assertTextContains("değiştirilmeden", substring = true)
            .assertTextContains(SORA_EDITOR_SOURCE, substring = true)
            .assertTextContains(APP_SOURCE, substring = true)
    }

    @Test
    fun `a library shows its version in Turkish`() {
        compose.showLicenses()
        val library = LIBRARIES.first { it.license == LicenseText.LGPL_2_1 }

        compose.scrolledTo(libraryTag(library))
            .assertTextContains("Sürüm ${library.version}", substring = true)
            .assertTextContains(library.license.shortName, substring = true)
    }

    @Test
    fun `the screen is accessible at the largest text in Turkish`() {
        compose.showLicenses(fontScale = LARGE_FONT_SCALE)
        compose.assertAccessible()

        compose.scrolledTo(licenseTextTag(LicenseText.MIT_JONI)).performClick()
        compose.scrolledTo(licenseTextBodyTag(LicenseText.MIT_JONI))
        compose.assertAccessible()
    }
}
