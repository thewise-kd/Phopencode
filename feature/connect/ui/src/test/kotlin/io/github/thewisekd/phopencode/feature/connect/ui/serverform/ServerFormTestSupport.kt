package io.github.thewisekd.phopencode.feature.connect.ui.serverform

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyId
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormState

internal val NEW_SERVER = ConnectRoute.ServerForm()

/** Made-up keys: the blobs are not real keys and log in nowhere. */
internal val WORK_KEY = SshKeyEntry(
    id = SshKeyId("key-work"),
    name = "Work laptop",
    type = "ssh-ed25519",
    fingerprint = "SHA256:testOnlyFingerprintWork",
    publicKey = "ssh-ed25519 AAAAtestonlynotarealkeyWork test@example.invalid",
    privateKeyRef = SecretRef("secret-key-work"),
)

internal val HOME_KEY = SshKeyEntry(
    id = SshKeyId("key-home"),
    name = "Home",
    type = "ssh-rsa",
    fingerprint = "SHA256:testOnlyFingerprintHome",
    publicKey = "ssh-rsa AAAAtestonlynotarealkeyHome",
    privateKeyRef = SecretRef("secret-key-home"),
)

/** A loaded form for a new server. */
internal fun loadedState(isNew: Boolean = true) = ServerFormState(isNew = isNew, loading = false)

internal fun ComposeContentTestRule.setForm(
    state: () -> ServerFormState,
    route: ConnectRoute.ServerForm = NEW_SERVER,
    onIntent: (ServerFormIntent) -> Unit = {},
) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                ServerFormScreen(route = route, state = state(), onIntent = onIntent)
            }
        }
    }
}

/** Float rounding slack, the same as Compose's own `assertTouchHeightIsEqualTo`. */
private val TOUCH_SIZE_TOLERANCE = 0.5.dp

/** Asserts the node's touch bounds are at least [minimum] in both directions. */
internal fun SemanticsNodeInteraction.assertTouchSizeIsAtLeast(minimum: Dp): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("Failed to check the touch size.")
    val bounds: Rect = node.touchBoundsInRoot
    val (width, height) = with(node.layoutInfo.density) { bounds.width.toDp() to bounds.height.toDp() }
    if (width < minimum - TOUCH_SIZE_TOLERANCE || height < minimum - TOUCH_SIZE_TOLERANCE) {
        throw AssertionError("Actual touch size is $width x $height, expected at least $minimum")
    }
    return this
}
