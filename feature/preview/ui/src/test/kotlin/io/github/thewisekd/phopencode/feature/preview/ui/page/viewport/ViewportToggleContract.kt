package io.github.thewisekd.phopencode.feature.preview.ui.page.viewport

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageStatus
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ViewportMode
import io.github.thewisekd.phopencode.feature.preview.logic.viewport.ViewportChange
import io.github.thewisekd.phopencode.feature.preview.logic.viewport.ViewportSwitch
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

/** The viewport toggle's texts in one language. */
internal data class ExpectedViewportStrings(val toggle: String, val desktop: String, val mobile: String)

/** What the viewport toggle shows and sends, the same in each language; a subclass sets the language. */
abstract class ViewportToggleContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedViewportStrings

    private val sent = mutableListOf<PageIntent>()

    private var mode by mutableStateOf(ViewportMode.Mobile)
    private var enabled by mutableStateOf(true)

    private fun show(start: ViewportMode, enabledAtStart: Boolean = true) {
        mode = start
        enabled = enabledAtStart
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                Surface { ViewportToggle(mode = mode, onIntent = { sent += it }, enabled = enabled) }
            }
        }
    }

    private fun stateDescription(): String? =
        compose.onNodeWithTag(ViewportTags.TOGGLE).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun `mobile reads its name and the off state`() {
        show(ViewportMode.Mobile)

        compose.onNodeWithTag(ViewportTags.TOGGLE)
            .assertIsDisplayed()
            .assertContentDescriptionEquals(strings.toggle)
            .assertIsOff()
            .assertIsEnabled()
        assertEquals(strings.mobile, stateDescription())
    }

    @Test
    fun `desktop reads its name and the on state`() {
        show(ViewportMode.Desktop)

        compose.onNodeWithContentDescription(strings.toggle).assertIsOn()
        assertEquals(strings.desktop, stateDescription())
    }

    @Test
    fun `tapping asks for the other mode, and the page logic's answer redraws it`() {
        show(ViewportMode.Mobile)

        compose.onNodeWithTag(ViewportTags.TOGGLE).performClick()
        assertEquals(listOf<PageIntent>(PageIntent.SetViewport(ViewportMode.Desktop)), sent)

        // The toggle holds no state of its own: it shows what the page logic says.
        compose.onNodeWithTag(ViewportTags.TOGGLE).assertIsOff()
        val page = PageState(port = 5173, status = PageStatus.Loaded)
        val change = ViewportSwitch.switch(page, (sent.single() as PageIntent.SetViewport).mode) as ViewportChange.Apply
        mode = change.state.viewport
        compose.waitForIdle()
        compose.onNodeWithTag(ViewportTags.TOGGLE).assertIsOn()
        assertEquals(strings.desktop, stateDescription())

        compose.onNodeWithTag(ViewportTags.TOGGLE).performClick()
        assertEquals(PageIntent.SetViewport(ViewportMode.Mobile), sent.last())
    }

    @Test
    fun `a closed page cannot switch`() {
        show(ViewportMode.Mobile, enabledAtStart = false)

        compose.onNodeWithTag(ViewportTags.TOGGLE).assertIsNotEnabled().performClick()

        assertEquals(emptyList(), sent)
    }

    @Test
    fun `its target is at least 48 dp each way`() {
        show(ViewportMode.Mobile)

        compose.onNodeWithTag(ViewportTags.TOGGLE).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }
}
