package io.github.thewisekd.phopencode.core.ui.component

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme

internal const val BUTTON_LABEL = "Do it"
internal const val MESSAGE = "Some message"
internal const val SCREEN_TITLE = "Some screen"

internal fun ComposeContentTestRule.setThemedContent(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    setContent {
        PhopencodeTheme(darkTheme = darkTheme) {
            Surface(content = content)
        }
    }
}

/** The color a text node really draws with. Read it from the unmerged tree. */
internal fun SemanticsNodeInteraction.textColor(): Color {
    val layouts = mutableListOf<TextLayoutResult>()
    val getLayout = fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
    checkNotNull(getLayout).invoke(layouts)
    return layouts.single().layoutInput.style.color
}

internal fun hasStateDescription(expected: String) =
    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)

internal val isIndeterminateProgress =
    SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)

internal val isLiveRegion = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

internal val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
