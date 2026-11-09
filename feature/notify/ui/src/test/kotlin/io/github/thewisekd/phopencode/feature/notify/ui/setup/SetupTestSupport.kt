package io.github.thewisekd.phopencode.feature.notify.ui.setup

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyServiceStatus
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyServer
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyTopic
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupState
import java.util.Collections

/** A made-up topic: tests check that it never reaches the screen. */
internal const val TOPIC_TEXT = "FakeTopicForScreenTests01"

internal val topic: NtfyTopic = requireNotNull(NtfyTopic.of(TOPIC_TEXT))

/** The subscription link of [topic] on the default server. */
internal val link: String = NtfyServer.DEFAULT.subscriptionLink(topic)

/** Loaded, on the default server, with the service running. */
internal val loadedState = SetupState(
    loading = false,
    subscriptionLink = link,
    status = NotifyServiceStatus.Running,
)

/** Where the "get the app" button must send the user: ntfy's own page on its phone apps. */
internal const val EXPECTED_APP_PAGE = "https://docs.ntfy.sh/subscribe/phone/"

/** Records the links the screen opens; [missing] makes it fail as Android does when no app handles one. */
internal class FakeUriHandler(var missing: Boolean = false) : UriHandler {
    val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun openUri(uri: String) {
        // Compose's AndroidUriHandler wraps ActivityNotFoundException in this, with the URI in the message.
        if (missing) throw IllegalArgumentException("Can't open $uri.")
        opened += uri
    }
}

/** The intents the screen sent and the links it opened. */
internal class SetupHarness(val uris: FakeUriHandler) {
    val intents: MutableList<SetupIntent> = Collections.synchronizedList(mutableListOf())
}

/** Shows the setup with [state] in the light theme, with [uris] as the system's link handler. */
internal fun ComposeContentTestRule.showSetup(
    state: SetupState,
    uris: FakeUriHandler = FakeUriHandler(),
): SetupHarness {
    val harness = SetupHarness(uris)
    setContent {
        PhopencodeTheme(darkTheme = false) {
            CompositionLocalProvider(LocalUriHandler provides uris) {
                Surface {
                    SetupScreen(NotifyRoute.Setup, state, onIntent = { harness.intents += it })
                }
            }
        }
    }
    return harness
}

/** Every node, merged or not: the texts, descriptions and tags a test can see. */
internal val anyNode = SemanticsMatcher("any node") { true }

/** Everything [node] says to a user or a test, as plain strings. */
internal fun spokenBy(node: SemanticsNode): List<String> {
    val config = node.config
    return buildList {
        config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
        config.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
        config.getOrNull(SemanticsProperties.ContentDescription)?.let { addAll(it) }
        config.getOrNull(SemanticsProperties.StateDescription)?.let { add(it) }
        config.getOrNull(SemanticsProperties.TestTag)?.let { add(it) }
        config.getOrNull(SemanticsProperties.PaneTitle)?.let { add(it) }
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
