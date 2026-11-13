package io.github.thewisekd.phopencode.feature.preview.ui.page

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageStatus
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ViewportMode
import io.github.thewisekd.phopencode.feature.preview.ui.page.viewport.ViewportTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** core/ui's top bar back button, in English. */
private const val BACK = "Go back"

/**
 * What the preview page sends and which controls it offers; the texts are in English and checked in
 * [PageLanguageContract]. Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class PageScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<PageIntent>()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    /** Each problem state, with the tag of the panel that explains it. */
    private val problems = listOf(
        connectionLost to PageTags.CONNECTION_LOST,
        failedToOpen(PreviewFailure.ConnectionLost) to PageTags.CONNECTION_LOST,
        nothingListens to PageTags.NOTHING_LISTENS,
        failedToOpen(PreviewFailure.NothingListens) to PageTags.NOTHING_LISTENS,
        httpError to PageTags.LOAD_ERROR,
        networkError to PageTags.LOAD_ERROR,
        failedToOpen(PreviewFailure.NotConnected) to PageTags.LOAD_ERROR,
        failedToOpen(PreviewFailure.BridgeFailed) to PageTags.LOAD_ERROR,
        failedToOpen(PreviewFailure.Unknown) to PageTags.LOAD_ERROR,
    )

    @Test
    fun `the root keeps the route's placeholder tag in every state`() {
        val states = PageStates(compose) { sent += it }
        (listOf(opening, closed, askingExternal) + problems.map { it.first }).forEach { state ->
            states.show(state)
            compose.onNodeWithTag("placeholder:$pageRoute").assertIsDisplayed()
        }
    }

    @Test
    fun `the reload button reloads, and is a 48 dp target`() {
        compose.setPageScreen(opening) { sent += it }

        compose.onNodeWithTag(PageTags.RELOAD)
            .assertIsEnabled()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(listOf<PageIntent>(PageIntent.Reload), sent)
    }

    @Test
    fun `a closed preview offers no reload`() {
        compose.setPageScreen(closed) { sent += it }

        compose.onNodeWithTag(PageTags.RELOAD).assertIsNotEnabled()
    }

    @Test
    fun `the top bar's viewport toggle shows the page's mode and asks for the other one`() {
        val states = PageStates(compose) { sent += it }
        states.show(opening.copy(status = PageStatus.Loaded))

        compose.onNodeWithTag(ViewportTags.TOGGLE)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertIsOff()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(listOf<PageIntent>(PageIntent.SetViewport(ViewportMode.Desktop)), sent)

        sent.clear()
        states.show(opening.copy(status = PageStatus.Loaded, viewport = ViewportMode.Desktop))
        compose.onNodeWithTag(ViewportTags.TOGGLE).assertIsOn().performClick()
        assertEquals(listOf<PageIntent>(PageIntent.SetViewport(ViewportMode.Mobile)), sent)
    }

    @Test
    fun `a closed preview offers no viewport switch`() {
        compose.setPageScreen(closed) { sent += it }

        compose.onNodeWithTag(ViewportTags.TOGGLE).assertIsNotEnabled().assertIsOff()
    }

    @Test
    fun `back closes the preview`() {
        compose.setPageScreen(opening) { sent += it }

        compose.onNodeWithContentDescription(BACK).performClick()

        assertEquals(listOf<PageIntent>(PageIntent.Close), sent)
    }

    @Test
    fun `each problem has its panel and a reload action`() {
        val states = PageStates(compose) { sent += it }
        problems.forEach { (state, tag) ->
            sent.clear()
            states.show(state)

            compose.onNodeWithTag(tag).assertIsDisplayed()
            compose.onNodeWithText("Reload").assertHeightIsAtLeast(48.dp).performClick()

            assertEquals(listOf<PageIntent>(PageIntent.Reload), sent, "$tag")
        }
    }

    @Test
    fun `pulling a problem down reloads`() {
        val states = PageStates(compose) { sent += it }
        listOf(connectionLost, nothingListens, httpError).forEach { state ->
            sent.clear()
            states.show(state)

            compose.onNodeWithTag(PageTags.CONTENT).performTouchInput { swipeDown() }
            compose.waitForIdle()

            assertEquals(listOf<PageIntent>(PageIntent.Reload), sent)
        }
    }

    @Test
    fun `a closed preview shows no WebView and no problem`() {
        compose.setPageScreen(closed) { sent += it }

        listOf(PageTags.WEB_VIEW, PageTags.OPENING, PageTags.LOAD_ERROR, PageTags.CONNECTION_LOST, PageTags.NOTHING_LISTENS)
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    }

    @Test
    fun `nothing opens outside the app until the address is confirmed`() {
        compose.setPageScreen(askingExternal) { sent += it }

        compose.onNodeWithTag(PageTags.EXTERNAL_DIALOG).assertIsDisplayed()
        assertNull(shadowOf(application).nextStartedActivity)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `confirming opens the shown address in another app`() {
        compose.setPageScreen(askingExternal) { sent += it }

        compose.onNodeWithText("Open").performClick()

        val started = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse(EXTERNAL_URL), started.data)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals(listOf<PageIntent>(PageIntent.ConfirmExternal), sent)
    }

    @Test
    fun `cancelling keeps the page and opens nothing`() {
        compose.setPageScreen(askingExternal) { sent += it }

        compose.onNodeWithText("Cancel").performClick()

        assertNull(shadowOf(application).nextStartedActivity)
        assertEquals(listOf<PageIntent>(PageIntent.DismissExternal), sent)
    }
}
