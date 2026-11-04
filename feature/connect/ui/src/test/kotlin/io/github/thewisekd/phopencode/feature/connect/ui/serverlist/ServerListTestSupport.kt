package io.github.thewisekd.phopencode.feature.connect.ui.serverlist

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme

/** Made-up servers: `.invalid` hosts never resolve, so no real address is in the tests. */
internal val firstServer = ServerProfile(
    id = ServerProfileId("profile-1"),
    name = "Build box",
    host = "build.example.invalid",
    username = "dev",
    auth = SshAuth.PrivateKey(SecretRef("ref-1")),
)

internal val secondServer = ServerProfile(
    id = ServerProfileId("profile-2"),
    name = "Lab",
    host = "lab.example.invalid",
    port = 2222,
    username = "ops",
    auth = SshAuth.Password(SecretRef("ref-2")),
)

internal fun ComposeContentTestRule.setThemedContent(content: @Composable () -> Unit) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface(content = content)
        }
    }
}

/** The smallest size of anything tappable, as `PhopencodeSpacing.touchTarget`. */
internal val TOUCH_TARGET = 48.dp

/** Float rounding slack, the same as Compose's own `assertTouchHeightIsEqualTo`. */
private val TOUCH_SIZE_TOLERANCE = 0.5.dp

/** Asserts the node's touch bounds are at least [minimum] in both directions. */
internal fun SemanticsNodeInteraction.assertTouchSizeIsAtLeast(minimum: Dp = TOUCH_TARGET): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("Failed to check the touch size.")
    val bounds: Rect = node.touchBoundsInRoot
    val (width, height) = with(node.layoutInfo.density) { bounds.width.toDp() to bounds.height.toDp() }
    if (width < minimum - TOUCH_SIZE_TOLERANCE || height < minimum - TOUCH_SIZE_TOLERANCE) {
        throw AssertionError("Actual touch size is $width x $height, expected at least $minimum")
    }
    return this
}
