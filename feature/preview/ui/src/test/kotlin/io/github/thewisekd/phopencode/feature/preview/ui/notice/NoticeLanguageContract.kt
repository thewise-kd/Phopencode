package io.github.thewisekd.phopencode.feature.preview.ui.notice

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.feature.preview.ui.ports.LARGE_FONT_SCALE
import io.github.thewisekd.phopencode.feature.preview.ui.ports.assertAccessible
import io.github.thewisekd.phopencode.feature.preview.ui.ports.assertErrorAnnounced
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The texts of the live preview notice in one language. */
internal data class ExpectedNoticeStrings(
    val title: String,
    /** In the order they show: loopback, trust, accounts, links, the app and the server. */
    val points: List<String>,
    val accept: String,
    val notNow: String,
    val saveFailed: String,
    /** Words the calm notice must not use in this language. */
    val alarming: List<String>,
)

/** What the notice shows, the same in each language; a subclass sets the language and its texts. */
abstract class NoticeLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedNoticeStrings

    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    @Test
    fun `the dialog shows its title as a heading`() {
        NoticeOverPorts(compose).show(showing)

        inDialog(strings.title).assertIsDisplayed()
        compose.onNode(hasText(strings.title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertIsDisplayed()
    }

    @Test
    fun `every point is read out on its own`() {
        assertEquals(5, strings.points.size)
        assertEquals(strings.points.size, strings.points.toSet().size, "points must differ")
        NoticeOverPorts(compose).show(showing)

        strings.points.forEach { point ->
            inDialog(point).performScrollTo().assertIsDisplayed()
        }
    }

    /** The answers sit below the scrolling text, outside it: they are asserted, never scrolled to. */
    @Test
    fun `both answers are labelled`() {
        NoticeOverPorts(compose).show(showing)

        inDialog(strings.accept).assertIsDisplayed()
        inDialog(strings.notNow).assertIsDisplayed()
    }

    @Test
    fun `a failed save says so and is announced`() {
        NoticeOverPorts(compose).show(saveFailed)

        inDialog(strings.saveFailed).performScrollTo().assertIsDisplayed()
        compose.assertErrorAnnounced(strings.saveFailed)
    }

    @Test
    fun `the wording stays calm`() {
        val texts = listOf(strings.title, strings.accept, strings.notNow, strings.saveFailed) + strings.points
        texts.forEach { text ->
            strings.alarming.forEach { word ->
                assertFalse(word in text.lowercase(), "\"$text\" uses \"$word\"")
            }
        }
    }

    @Test
    fun `the largest text scrolls to every point and keeps 48 dp targets`() {
        NoticeOverPorts(compose, fontScale = LARGE_FONT_SCALE).show(showing)

        // The points scroll in the dialog's text; the answers stay below it, always in view.
        strings.points.forEach { point -> inDialog(point).performScrollTo().assertIsDisplayed() }
        inDialog(strings.accept).assertIsDisplayed()
        inDialog(strings.notNow).assertIsDisplayed()
        compose.assertAccessible()
    }

    @Test
    fun `the largest text with a failed save stays accessible`() {
        NoticeOverPorts(compose, fontScale = LARGE_FONT_SCALE).show(saveFailed)

        inDialog(strings.saveFailed).performScrollTo().assertIsDisplayed()
        compose.assertAccessible()
    }
}
