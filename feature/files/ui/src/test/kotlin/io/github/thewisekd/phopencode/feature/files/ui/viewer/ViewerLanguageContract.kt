package io.github.thewisekd.phopencode.feature.files.ui.viewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerContent
import io.github.thewisekd.phopencode.feature.files.logic.contract.ViewerState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the viewer in one language; sizes are written as the language writes numbers. */
internal data class ExpectedViewerStrings(
    val loading: String,
    val edit: String,
    val rendered: String,
    val raw: String,
    val malformed: String,
    val binaryTitle: String,
    /** The message for a binary file of 2,048 bytes. */
    val binaryMessage: String,
    val tooLargeTitle: String,
    /** 3 MiB against a 1 MiB limit, below the hard limit. */
    val tooLargeWarning: String,
    /** 100 MiB against a 10 MiB hard limit. */
    val tooLargeBlocked: String,
    val openAnyway: String,
    val errors: Map<FilesFailure, String>,
    val retry: String,
    /** The preview of `logo.png`. */
    val imageDescription: String,
    /** Bytes to the size shown in the binary message. */
    val sizes: Map<Long, String>,
)

/** What the viewer says, the same in each language; a subclass sets the language and its texts. */
abstract class ViewerLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedViewerStrings

    @Test
    fun `loading says the file is opening`() {
        compose.setViewer(ViewerState(path = CODE_PATH))

        compose.onNodeWithTag(ViewerTags.LOADING).assertTextEquals(strings.loading)
    }

    @Test
    fun `the edit button is labelled`() {
        compose.setViewer(CODE)

        compose.onNodeWithTag(ViewerTags.EDIT).assertTextEquals(strings.edit)
    }

    @Test
    fun `the Markdown modes are labelled`() {
        compose.setViewer(MARKDOWN)

        compose.onNodeWithTag(ViewerTags.MODE_RENDERED).assertTextEquals(strings.rendered)
        compose.onNodeWithTag(ViewerTags.MODE_RAW).assertTextEquals(strings.raw)
    }

    @Test
    fun `malformed text is explained`() {
        compose.setViewer(MALFORMED)

        compose.onNodeWithTag(ViewerTags.MALFORMED).assertTextEquals(strings.malformed)
    }

    @Test
    fun `a binary file gives its size`() {
        compose.setViewer(loaded("bin/app", ViewerContent.Binary(sizeBytes = 2_048)))

        compose.onNodeWithText(strings.binaryTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.binaryMessage).assertIsDisplayed()
    }

    @Test
    fun `a large file gives its size and the limit`() {
        val big = ViewerContent.TooLarge(sizeBytes = 3_145_728, limitBytes = 1_048_576, canOpenAnyway = true)
        val harness = compose.setViewer(loaded("logs/app.log", big))

        compose.onNodeWithText(strings.tooLargeTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.tooLargeWarning).assertIsDisplayed()
        compose.onNodeWithTag(ViewerTags.OPEN_ANYWAY).assertTextEquals(strings.openAnyway)

        harness.state.value = loaded(
            "logs/app.log",
            ViewerContent.TooLarge(sizeBytes = 104_857_600, limitBytes = 10_485_760, canOpenAnyway = false),
        )
        compose.onNodeWithText(strings.tooLargeBlocked).assertIsDisplayed()
    }

    @Test
    fun `every failure is worded`() {
        assertEquals(FilesFailure.entries.toSet(), strings.errors.keys)
        val harness = compose.setViewer(ViewerState(path = CODE_PATH, loading = false, error = FilesFailure.Unknown))
        strings.errors.forEach { (failure, message) ->
            harness.state.value = ViewerState(path = CODE_PATH, loading = false, error = failure)
            compose.onNodeWithText(message).assertIsDisplayed()
        }
        compose.onNodeWithText(strings.retry).assertIsDisplayed()
    }

    @Test
    fun `the image preview is described`() {
        compose.setViewer(IMAGE)

        compose.onNodeWithContentDescription(strings.imageDescription).assertExists()
    }

    @Test
    fun `sizes are written in the language's format`() {
        val harness = compose.setViewer(loaded("bin/app", ViewerContent.Binary(sizeBytes = 0)))
        strings.sizes.forEach { (bytes, shown) ->
            harness.state.value = loaded("bin/app", ViewerContent.Binary(sizeBytes = bytes))
            compose.onNodeWithText(shown, substring = true).assertIsDisplayed()
        }
    }
}
