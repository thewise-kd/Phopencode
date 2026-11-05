package io.github.thewisekd.phopencode.feature.files.ui.viewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerMode
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the viewer draws for each state of the contract and what it sends, in English: the texts of
 * both languages are in `ViewerLanguageContract`. The code view is a sora editor inside an
 * `AndroidView`; whether its text is highlighted only the emulator shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ViewerBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the root keeps the route's tag and the title is the file's name`() {
        val harness = compose.setViewer(CODE, routeOf(CODE_PATH, line = 42))

        compose.onNodeWithTag("placeholder:${harness.route}").assertIsDisplayed()
        compose.onNodeWithText("main kodu.kt").assertIsDisplayed()
    }

    @Test
    fun `back sends Close`() {
        val harness = compose.setViewer(CODE)

        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf<ViewerIntent>(ViewerIntent.Close), harness.intents)
    }

    @Test
    fun `code shows in the code view, without the Markdown modes`() {
        compose.setViewer(CODE)

        compose.onNodeWithTag(ViewerTags.CODE).assertIsDisplayed()
        assertFalse(compose.hasTag(ViewerTags.MARKDOWN))
        assertFalse(compose.hasTag(ViewerTags.MODE_RENDERED))
        assertFalse(compose.hasTag(ViewerTags.MODE_RAW))
        assertFalse(compose.hasTag(ViewerTags.MALFORMED))
    }

    @Test
    fun `clean text can be edited`() {
        val harness = compose.setViewer(CODE)

        compose.onNodeWithTag(ViewerTags.EDIT).performClick()

        assertEquals(listOf<ViewerIntent>(ViewerIntent.Edit), harness.intents)
    }

    @Test
    fun `rendered Markdown shows rendered, and Raw switches to the raw view`() {
        val harness = compose.setViewer(MARKDOWN)

        compose.awaitText("Başlık")
        compose.onNodeWithTag(ViewerTags.MARKDOWN).assertIsDisplayed()
        assertFalse(compose.hasTag(ViewerTags.CODE))
        compose.onNodeWithTag(ViewerTags.MODE_RENDERED).assertIsSelected()
        compose.onNodeWithTag(ViewerTags.MODE_RAW).assertIsNotSelected().performClick()

        assertEquals(listOf<ViewerIntent>(ViewerIntent.SetMode(ViewerMode.Raw)), harness.intents)
    }

    @Test
    fun `raw Markdown shows in the code view, and Rendered switches back`() {
        val harness = compose.setViewer(MARKDOWN.copy(mode = ViewerMode.Raw))

        compose.onNodeWithTag(ViewerTags.CODE).assertIsDisplayed()
        assertFalse(compose.hasTag(ViewerTags.MARKDOWN))
        compose.onNodeWithTag(ViewerTags.MODE_RAW).assertIsSelected()
        compose.onNodeWithTag(ViewerTags.MODE_RENDERED).assertIsNotSelected().performClick()

        assertEquals(listOf<ViewerIntent>(ViewerIntent.SetMode(ViewerMode.Rendered)), harness.intents)
    }

    @Test
    fun `tapping the mode already shown sends nothing`() {
        val harness = compose.setViewer(MARKDOWN)

        compose.onNodeWithTag(ViewerTags.MODE_RENDERED).performClick()

        assertTrue(harness.intents.isEmpty())
    }

    @Test
    fun `the mode follows the state`() {
        val harness = compose.setViewer(MARKDOWN.copy(mode = ViewerMode.Raw))
        compose.onNodeWithTag(ViewerTags.CODE).assertIsDisplayed()

        harness.state.value = MARKDOWN

        compose.awaitText("Başlık")
        assertFalse(compose.hasTag(ViewerTags.CODE))
        compose.onNodeWithTag(ViewerTags.MODE_RENDERED).assertIsSelected()
    }

    @Test
    fun `malformed text warns and cannot be edited`() {
        compose.setViewer(MALFORMED)

        compose.onNodeWithTag(ViewerTags.MALFORMED).assertIsDisplayed()
        compose.onNodeWithTag(ViewerTags.CODE).assertIsDisplayed()
        assertFalse(compose.hasTag(ViewerTags.EDIT))
    }

    @Test
    fun `loading shows a still line of text and nothing to edit`() {
        compose.setViewer(ViewerState(path = CODE_PATH))

        compose.onNodeWithTag(ViewerTags.LOADING).assertIsDisplayed()
        assertFalse(compose.hasTag(ViewerTags.CODE))
        assertFalse(compose.hasTag(ViewerTags.EDIT))
    }

    @Test
    fun `a refresh keeps the loaded text on screen`() {
        compose.setViewer(CODE.copy(loading = true))

        compose.onNodeWithTag(ViewerTags.CODE).assertIsDisplayed()
        assertFalse(compose.hasTag(ViewerTags.LOADING))
        assertFalse(compose.hasTag(ViewerTags.EDIT))
    }

    @Test
    fun `a binary file says so and cannot be edited`() {
        compose.setViewer(loaded("bin/app", ViewerContent.Binary(sizeBytes = 2_048)))

        compose.onNodeWithTag(ViewerTags.BINARY).assertIsDisplayed()
        assertFalse(compose.hasTag(ViewerTags.CODE))
        assertFalse(compose.hasTag(ViewerTags.EDIT))
    }

    @Test
    fun `a large file below the hard limit opens anyway on request`() {
        val big = ViewerContent.TooLarge(sizeBytes = 3_145_728, limitBytes = 1_048_576, canOpenAnyway = true)
        val harness = compose.setViewer(loaded("logs/app.log", big))

        compose.onNodeWithTag(ViewerTags.TOO_LARGE).assertIsDisplayed()
        compose.onNodeWithTag(ViewerTags.OPEN_ANYWAY).performScrollTo().performClick()

        assertEquals(listOf<ViewerIntent>(ViewerIntent.OpenAnyway), harness.intents)
    }

    @Test
    fun `a file above the hard limit offers no way to open it`() {
        val huge = ViewerContent.TooLarge(sizeBytes = 104_857_600, limitBytes = 10_485_760, canOpenAnyway = false)
        compose.setViewer(loaded("logs/app.log", huge))

        compose.onNodeWithTag(ViewerTags.TOO_LARGE).assertIsDisplayed()
        assertFalse(compose.hasTag(ViewerTags.OPEN_ANYWAY))
    }

    @Test
    fun `an error shows its reason and retries`() {
        val harness = compose.setViewer(ViewerState(path = CODE_PATH, loading = false, error = FilesFailure.NotFound))

        compose.onNodeWithTag(ViewerTags.ERROR).assertIsDisplayed()
        compose.onNodeWithText("Try again").performScrollTo().performClick()

        assertEquals(listOf<ViewerIntent>(ViewerIntent.Retry), harness.intents)
        assertFalse(compose.hasTag(ViewerTags.EDIT))
    }

    @Test
    fun `an image shows as a preview named after the file`() {
        compose.setViewer(IMAGE)

        compose.onNodeWithTag(ViewerTags.IMAGE).assertIsDisplayed()
        compose.onNodeWithContentDescription("logo.png", substring = true).assertExists()
        assertFalse(compose.hasTag(ViewerTags.CODE))
        assertFalse(compose.hasTag(ViewerTags.EDIT))
    }
}
