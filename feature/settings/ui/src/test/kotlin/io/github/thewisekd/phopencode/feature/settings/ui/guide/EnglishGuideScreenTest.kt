package io.github.thewisekd.phopencode.feature.settings.ui.guide

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuidePage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The guide in English: every page's title and the facts it must give, as `docs/INSTALL.md` and
 * the real screens say them, the buttons and the page position.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishGuideScreenTest {
    @get:Rule
    val compose = createComposeRule()

    /** Each page's title, then words its text must hold. */
    private val pages: Map<GuidePage, Pair<String, List<String>>> = mapOf(
        GuidePage.Welcome to ("Welcome to Phopencode" to listOf("opencode", "your own server", "this phone")),
        GuidePage.OnYourServer to (
            "On your server" to listOf("Install opencode", "opencode serve", "127.0.0.1", "password", "SSH", "no port")
            ),
        GuidePage.AddServer to (
            "Add a server" to listOf("Add server", "address", "user name", "password", "SSH key", "fingerprint")
            ),
        GuidePage.SessionsAndChat to (
            "Sessions and chat" to listOf("project folder", "New chat", "question", "permission")
            ),
        GuidePage.FilesAndGit to ("Files and Git" to listOf("Open files", "Open git", "Session actions")),
        GuidePage.Notifications to ("Notifications (optional)" to listOf("ntfy", "Notifications", "server")),
        GuidePage.LivePreview to (
            "Live preview" to listOf("dev server yourself", "127.0.0.1", "Live preview", "Session actions")
            ),
        GuidePage.Done to ("You are ready" to listOf("Settings", "Usage guide")),
    )

    @Test
    fun `every page has its title and its facts in English`() {
        compose.assertPages(pages)
    }

    @Test
    fun `the screen and its buttons are in English`() {
        compose.showGuide(GuidePage.FilesAndGit)

        compose.onNodeWithText("Usage guide").assertIsDisplayed()
        compose.onNodeWithTag(GUIDE_BACK_TAG).assertTextEquals("Back")
        compose.onNodeWithTag(GUIDE_NEXT_TAG).assertTextEquals("Next")
        compose.onNodeWithTag(GUIDE_SKIP_TAG).assertTextEquals("Skip")
    }

    @Test
    fun `the last page starts`() {
        compose.showGuide(GuidePage.Done)

        compose.onNodeWithTag(GUIDE_START_TAG).assertTextEquals("Start")
    }

    @Test
    fun `the position is read out`() {
        compose.showGuide(GuidePage.AddServer)

        compose.onNodeWithContentDescription("Page 3 of 8").assertIsDisplayed()
    }
}
