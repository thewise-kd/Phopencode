package io.github.thewisekd.phopencode.feature.connect.ui.hostkey

import androidx.compose.foundation.layout.height
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyRequest
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyState

/** Made-up fingerprints in the OpenSSH shape: no real server has them. */
internal val NEW_FINGERPRINT = HostKeyFingerprint("SHA256:not-a-real-fingerprint-new-0000000000000000")
internal val APPROVED_FINGERPRINT = HostKeyFingerprint("SHA256:not-a-real-fingerprint-old-1111111111111111")

internal const val KEY_TYPE = "ssh-ed25519"

internal val TEST_ROUTE = ConnectRoute.HostKey(serverId = "server-1")

internal val firstSeen = HostKeyRequest.FirstSeen(KEY_TYPE, NEW_FINGERPRINT)
internal val changed = HostKeyRequest.Changed(KEY_TYPE, NEW_FINGERPRINT, approved = APPROVED_FINGERPRINT)

internal fun asking(request: HostKeyRequest, deciding: Boolean = false) =
    HostKeyState(loading = false, request = request, deciding = deciding)

/**
 * The screen in the app theme, [height] tall if given, with [state] changeable by the test and every
 * intent recorded.
 */
internal class HostKeyScreenHarness(
    initial: HostKeyState,
    private val darkTheme: Boolean,
    private val height: Dp?,
) {
    val state: MutableState<HostKeyState> = mutableStateOf(initial)
    val intents = mutableListOf<HostKeyIntent>()

    /** The theme's colours, read inside the composition. */
    lateinit var colors: ColorScheme

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = darkTheme) {
            colors = MaterialTheme.colorScheme
            Surface(modifier = if (height != null) Modifier.height(height) else Modifier) {
                HostKeyScreen(route = TEST_ROUTE, state = state.value, onIntent = { intents += it })
            }
        }
    }
}

internal fun ComposeContentTestRule.setHostKeyScreen(
    state: HostKeyState,
    darkTheme: Boolean = false,
    height: Dp? = null,
): HostKeyScreenHarness {
    val harness = HostKeyScreenHarness(state, darkTheme, height)
    setContent { harness.Content() }
    return harness
}

private val TOUCH_SIZE_TOLERANCE = 0.5.dp

/** The node's touch bounds are at least [minimum] in both directions. */
internal fun SemanticsNodeInteraction.assertTouchSizeIsAtLeast(minimum: Dp): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("Failed to check the touch size.")
    val bounds: Rect = node.touchBoundsInRoot
    val (width, height) = with(node.layoutInfo.density) { bounds.width.toDp() to bounds.height.toDp() }
    if (width < minimum - TOUCH_SIZE_TOLERANCE || height < minimum - TOUCH_SIZE_TOLERANCE) {
        throw AssertionError("Touch size is $width x $height, expected at least $minimum")
    }
    return this
}
