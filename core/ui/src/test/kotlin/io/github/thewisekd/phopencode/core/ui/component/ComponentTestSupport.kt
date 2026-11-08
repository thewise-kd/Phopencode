package io.github.thewisekd.phopencode.core.ui.component

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme

internal const val BUTTON_LABEL = "Do it"
internal const val MESSAGE = "Some message"
internal const val SCREEN_TITLE = "Some screen"

internal fun ComposeContentTestRule.setThemedContent(
    darkTheme: Boolean = false,
    fontScale: Float? = null,
    content: @Composable () -> Unit,
) {
    setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
            PhopencodeTheme(darkTheme = darkTheme) {
                Surface(content = content)
            }
        }
    }
}

/** The layout a text node really draws. Read it from the unmerged tree. */
internal fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val layouts = mutableListOf<TextLayoutResult>()
    val getLayout = fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
    checkNotNull(getLayout).invoke(layouts)
    return layouts.single()
}

/** The color a text node really draws with. Read it from the unmerged tree. */
internal fun SemanticsNodeInteraction.textColor(): Color = textLayout().layoutInput.style.color

/** The most lines a text node may take. Read it from the unmerged tree. */
internal fun SemanticsNodeInteraction.maxLines(): Int = textLayout().layoutInput.maxLines

internal fun hasStateDescription(expected: String) =
    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)

internal val isIndeterminateProgress =
    SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)

internal val isLiveRegion = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

internal val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

/** Float rounding slack for touch size checks, the same as Compose's own `assertTouchHeightIsEqualTo`. */
private val TOUCH_SIZE_TOLERANCE = 0.5.dp

/** Asserts the node's touch bounds are at least [minimum] tall: accessibility sets a minimum, not an exact size. */
internal fun SemanticsNodeInteraction.assertTouchHeightIsAtLeast(minimum: Dp): SemanticsNodeInteraction =
    assertTouchSizeIsAtLeast("height", minimum) { it.height }

/** Asserts the node's touch bounds are at least [minimum] wide: accessibility sets a minimum, not an exact size. */
internal fun SemanticsNodeInteraction.assertTouchWidthIsAtLeast(minimum: Dp): SemanticsNodeInteraction =
    assertTouchSizeIsAtLeast("width", minimum) { it.width }

private fun SemanticsNodeInteraction.assertTouchSizeIsAtLeast(
    dimension: String,
    minimum: Dp,
    sizeInPx: (Rect) -> Float,
): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("Failed to check the touch $dimension.")
    val actual = with(node.layoutInfo.density) { sizeInPx(node.touchBoundsInRoot).toDp() }
    if (actual < minimum - TOUCH_SIZE_TOLERANCE) {
        throw AssertionError(
            "Actual touch $dimension is $actual, expected at least $minimum (tolerance: $TOUCH_SIZE_TOLERANCE)",
        )
    }
    return this
}
