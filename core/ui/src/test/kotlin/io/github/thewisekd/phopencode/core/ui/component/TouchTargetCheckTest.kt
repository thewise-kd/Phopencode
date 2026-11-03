package io.github.thewisekd.phopencode.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeSpacing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

private const val TARGET_TAG = "target"

/**
 * The minimum touch target checks pass at or above the minimum and fail below it.
 *
 * Compose widens the touch bounds of any node with a click action to the platform minimum (48dp), so a
 * `clickable` box always reports at least that. The plain targets here have no click action, so their
 * touch bounds are their real size and the checks must reject the ones below the minimum.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class TouchTargetCheckTest {
    @get:Rule
    val compose = createComposeRule()

    private val touchTarget = PhopencodeSpacing().touchTarget

    private fun setUnwidenedTarget(width: Dp, height: Dp) {
        compose.setThemedContent {
            Box(Modifier.size(width, height).testTag(TARGET_TAG))
        }
    }

    private fun setClickableTarget(width: Dp, height: Dp) {
        compose.setThemedContent {
            Box(Modifier.size(width, height).clickable {}.testTag(TARGET_TAG))
        }
    }

    @Test
    fun `a target shorter than the minimum fails the height check`() {
        setUnwidenedTarget(width = 100.dp, height = 40.dp)

        val error = assertFailsWith<AssertionError> {
            compose.onNodeWithTag(TARGET_TAG).assertTouchHeightIsAtLeast(touchTarget)
        }
        assertContains(error.message.orEmpty(), "height is 40.0.dp")
    }

    @Test
    fun `a target narrower than the minimum fails the width check`() {
        setUnwidenedTarget(width = 40.dp, height = 100.dp)

        val error = assertFailsWith<AssertionError> {
            compose.onNodeWithTag(TARGET_TAG).assertTouchWidthIsAtLeast(touchTarget)
        }
        assertContains(error.message.orEmpty(), "width is 40.0.dp")
    }

    @Test
    fun `a target exactly at the minimum passes`() {
        setUnwidenedTarget(width = touchTarget, height = touchTarget)

        compose.onNodeWithTag(TARGET_TAG)
            .assertTouchHeightIsAtLeast(touchTarget)
            .assertTouchWidthIsAtLeast(touchTarget)
    }

    @Test
    fun `a target larger than the minimum passes`() {
        setUnwidenedTarget(width = 52.dp, height = 52.dp)

        compose.onNodeWithTag(TARGET_TAG)
            .assertTouchHeightIsAtLeast(touchTarget)
            .assertTouchWidthIsAtLeast(touchTarget)
    }

    @Test
    fun `a small clickable target passes because its touch area is widened to the minimum`() {
        setClickableTarget(width = 40.dp, height = 40.dp)

        compose.onNodeWithTag(TARGET_TAG)
            .assertTouchHeightIsAtLeast(touchTarget)
            .assertTouchWidthIsAtLeast(touchTarget)
    }
}
