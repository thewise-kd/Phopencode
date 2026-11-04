package io.github.thewisekd.phopencode.feature.chat.ui.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.assertTrue

private const val ROOT = "test:root"
private const val COMPOSER = "test:composer"
private val TOLERANCE = 1.dp

private fun lines(count: Int) = (1..count).joinToString("\n") { "satır $it" }

/**
 * How the composer sits on screen: the field grows with the draft up to [COMPOSER_MAX_LINES] lines and
 * then scrolls inside, and the composer adds no insets of its own, so under a parent that pads for
 * the keyboard, as `ChatScreen` does with `safeDrawingPadding`, it sits right above the keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ComposerLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private fun fieldHeight(): Dp = compose.onNodeWithTag(COMPOSER_FIELD_TAG).getBoundsInRoot().let { it.bottom - it.top }

    private fun assertClose(expected: Dp, actual: Dp, what: String) =
        assertTrue(abs((expected - actual).value) <= TOLERANCE.value, "$what: expected $expected, was $actual")

    @Test
    fun `the field grows with the draft, then stops growing and scrolls`() {
        val harness = compose.setComposer(ready())
        val one = fieldHeight()

        harness.state.value = ready(lines(3))
        val three = fieldHeight()

        harness.state.value = ready(lines(COMPOSER_MAX_LINES))
        val most = fieldHeight()

        harness.state.value = ready(lines(40))
        val many = fieldHeight()

        assertTrue(three > one + TOLERANCE, "three lines ($three) must be taller than one ($one)")
        assertTrue(most > three + TOLERANCE, "$COMPOSER_MAX_LINES lines ($most) must be taller than three ($three)")
        assertClose(most, many, "forty lines stay as tall as $COMPOSER_MAX_LINES")
    }

    @Test
    fun `the composer adds no insets of its own`() {
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize().testTag(ROOT)) {
                    Box(Modifier.fillMaxSize()) {
                        Composer(
                            state = ready(DRAFT),
                            onIntent = {},
                            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).testTag(COMPOSER),
                        )
                    }
                }
            }
        }

        val root = compose.onNodeWithTag(ROOT).getBoundsInRoot()
        val composer = compose.onNodeWithTag(COMPOSER).getBoundsInRoot()
        val action = compose.onNodeWithTag(COMPOSER_ACTION_TAG).getBoundsInRoot()
        assertClose(root.bottom, composer.bottom, "the composer's bottom")
        // Its own padding is small: a navigation bar or keyboard padding would be far larger.
        assertTrue(composer.bottom - action.bottom <= 16.dp + TOLERANCE, "space under the button: ${composer.bottom - action.bottom}")
    }

    @Test
    fun `under a parent that pads for the keyboard, a long draft keeps the composer above it`() {
        val keyboard = 300.dp
        val screen = 640.dp
        val state = mutableStateOf(ready(lines(40)))
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                Surface(Modifier.height(screen).fillMaxWidth().testTag(ROOT)) {
                    // What safeDrawingPadding does while the keyboard is open, drawn here with a fixed inset.
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets(bottom = keyboard))) {
                        Box(Modifier.fillMaxWidth().weight(1f))
                        Composer(state = state.value, onIntent = {}, modifier = Modifier.fillMaxWidth().testTag(COMPOSER))
                    }
                }
            }
        }

        val root = compose.onNodeWithTag(ROOT).getBoundsInRoot()
        val composer = compose.onNodeWithTag(COMPOSER).getBoundsInRoot()
        val keyboardTop = root.bottom - keyboard
        assertClose(keyboardTop, composer.bottom, "the composer's bottom")
        assertTrue(composer.top >= root.top, "the composer fits above the keyboard: top ${composer.top}")
        compose.onNodeWithTag(COMPOSER_ACTION_TAG).assertIsDisplayed()
        val action = compose.onNodeWithTag(COMPOSER_ACTION_TAG).getBoundsInRoot()
        assertTrue(action.bottom <= keyboardTop + TOLERANCE, "the button stays above the keyboard: ${action.bottom}")

        // Running adds the delivery choice; the composer still fits.
        state.value = ComposerState(draft = lines(40), enabled = true, running = true)
        val running = compose.onNodeWithTag(COMPOSER).getBoundsInRoot()
        assertClose(keyboardTop, running.bottom, "the running composer's bottom")
        assertTrue(running.top >= root.top, "the running composer fits above the keyboard: top ${running.top}")
    }
}
