package io.github.thewisekd.phopencode.feature.settings.ui.licenses

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.feature.settings.ui.LARGE_FONT_SCALE
import io.github.thewisekd.phopencode.feature.settings.ui.assertAccessible
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The licenses in English: the notices, every library and grammar, the license texts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishLicensesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the screen is titled and goes back`() {
        val closed = compose.showLicenses()

        compose.onNodeWithText("Open source licenses").assertIsDisplayed()
        compose.onNodeWithTag("placeholder:${SettingsRoute.Licenses}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(1, closed())
    }

    @Test
    fun `the LGPL notice says where the source is and that the library may be replaced`() {
        compose.showLicenses()

        compose.scrolledTo(LGPL_NOTICE_TAG)
            .assertIsDisplayed()
            .assertTextContains("GNU Lesser General Public License", substring = true)
            .assertTextContains(SORA_EDITOR_SOURCE, substring = true)
            .assertTextContains(APP_SOURCE, substring = true)
            .assertTextContains("replace", substring = true)
    }

    @Test
    fun `the EPL notice says where the source is`() {
        compose.showLicenses()

        compose.scrolledTo(EPL_NOTICE_TAG)
            .assertIsDisplayed()
            .assertTextContains("Eclipse Public License", substring = true)
            .assertTextContains(ECLIPSE_SOURCE, substring = true)
    }

    @Test
    fun `every shipped library is listed with its version and license`() {
        compose.showLicenses()

        LIBRARIES.forEach { library ->
            compose.scrolledTo(libraryTag(library))
                .assertIsDisplayed()
                .assertTextContains(library.coordinate, substring = true)
                .assertTextContains("Version ${library.version}", substring = true)
                .assertTextContains(library.license.shortName, substring = true)
        }
    }

    @Test
    fun `every TextMate grammar and theme is listed with its license`() {
        compose.showLicenses()

        TEXTMATE_NOTICES.forEach { notice ->
            compose.scrolledTo(textMateTag(notice))
                .assertIsDisplayed()
                .assertTextContains(notice.name, substring = true)
                .assertTextContains(notice.license, substring = true)
        }
    }

    @Test
    fun `a license text opens and closes`() {
        compose.showLicenses()

        compose.scrolledTo(licenseTextTag(LicenseText.LGPL_2_1)).performClick()
        compose.scrolledTo(licenseTextBodyTag(LicenseText.LGPL_2_1))
            .assertTextContains("GNU LESSER GENERAL PUBLIC LICENSE", substring = true)

        compose.scrolledTo(licenseTextTag(LicenseText.LGPL_2_1)).performClick()
        compose.onNodeWithTag(licenseTextBodyTag(LicenseText.LGPL_2_1)).assertDoesNotExist()
    }

    @Test
    fun `a TextMate license opens with its files`() {
        compose.showLicenses()
        val kotlin = TEXTMATE_NOTICES.first { it.directory == "grammars/kotlin" }

        compose.scrolledTo(textMateTag(kotlin)).performClick()

        compose.scrolledTo(textMateBodyTag(kotlin)).assertTextContains("George Fraser", substring = true)
    }

    @Test
    fun `the screen is accessible at the largest text`() {
        compose.showLicenses(fontScale = LARGE_FONT_SCALE)
        compose.assertAccessible()

        compose.scrolledTo(licenseTextTag(LicenseText.APACHE_2_0)).performClick()
        compose.scrolledTo(licenseTextBodyTag(LicenseText.APACHE_2_0))
        compose.assertAccessible()
    }
}
