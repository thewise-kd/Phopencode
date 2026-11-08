package io.github.thewisekd.phopencode.feature.catalog.ui.status

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.api.McpServer
import io.github.thewisekd.phopencode.core.api.McpStatus
import io.github.thewisekd.phopencode.core.api.Plugin
import io.github.thewisekd.phopencode.core.api.PluginStatus
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme

/** Made-up entries: the `.invalid` host never resolves, so no real address is in the tests. */
internal const val FAILED_MCP_ERROR = "connect ECONNREFUSED docs.example.invalid:443"
internal const val FAILED_PLUGIN_ERROR = "Cannot find module 'opencode-broken'"

internal val connectedMcp = McpServer(name = "files", status = McpStatus.Connected)
internal val pendingMcp = McpServer(name = "search", status = McpStatus.Pending)
internal val disabledMcp = McpServer(name = "browser", status = McpStatus.Disabled)
internal val failedMcp = McpServer(name = "docs", status = McpStatus.Failed, error = FAILED_MCP_ERROR)
internal val authMcp = McpServer(name = "tracker", status = McpStatus.NeedsAuth)
internal val oddMcp = McpServer(name = "future", status = McpStatus.Unknown("sleeping"))

internal val allMcp = listOf(connectedMcp, pendingMcp, disabledMcp, failedMcp, authMcp, oddMcp)

internal val activePlugin = Plugin(id = "opencode-wakatime", status = PluginStatus.Active)
internal val failedPlugin = Plugin(id = "opencode-broken", status = PluginStatus.Failed, error = FAILED_PLUGIN_ERROR)
internal val unnamedPlugin = Plugin(id = null, status = PluginStatus.Unknown("loading"))

internal val allPlugins = listOf(activePlugin, failedPlugin, unnamedPlugin)

internal fun ComposeContentTestRule.setThemedContent(content: @Composable () -> Unit) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface(content = content)
        }
    }
}

/** Brings the list entry holding [text] into view; the list is lazy, so it may not be composed yet. */
internal fun ComposeContentTestRule.scrollToText(text: String, substring: Boolean = false) {
    onNodeWithTag(STATUS_LIST_TAG).performScrollToNode(hasText(text, substring = substring))
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
