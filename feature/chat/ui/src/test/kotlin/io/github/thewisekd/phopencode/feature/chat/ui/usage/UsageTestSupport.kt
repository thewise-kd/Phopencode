package io.github.thewisekd.phopencode.feature.chat.ui.usage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import java.math.BigInteger

/** The tag the harness puts on the bar's modifier, as the chat puts its slot tag there. */
internal const val BAR_TAG = "test:usage-bar"

internal val DETAIL_ROUTE = ChatRoute.TokenUsage(sessionId = "ses_1")

internal fun count(value: Long): BigInteger = BigInteger.valueOf(value)

/** Made-up counts, large enough to need grouping: 14,190 tokens in all. */
internal val USAGE = TokenUsage(
    input = count(1_234),
    output = count(567),
    reasoning = count(89),
    cacheRead = count(12_000),
    cacheWrite = count(300),
    cost = Cost.parse("0.1234"),
    hasMissingCost = false,
)

/** A loaded session with a known model, its limit and every count. */
internal val LOADED = UsageState(
    loading = false,
    usage = USAGE,
    model = ModelRef(providerId = "anthropic", modelId = "claude-sonnet-4"),
    contextLimit = 200_000,
    contextTokens = count(13_534),
    contextPercent = 7,
)

/** [USAGE] with [cost] as its total, a lower bound if [missing]. */
internal fun costing(cost: String?, missing: Boolean = false): UsageState =
    LOADED.copy(usage = USAGE.copy(cost = cost?.let { Cost.parse(it) }, hasMissingCost = missing))

/**
 * The bar in the app theme, with [state] changeable by the test and every intent recorded; with a
 * [title], as the chat's top bar draws it.
 */
internal class UsageBarHarness(initial: UsageState, private val title: String?) {
    val state: MutableState<UsageState> = mutableStateOf(initial)
    val intents = mutableListOf<UsageIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                // Scrollable so tests can scroll a node into view first: at API 35+ the insets push content down.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    UsageBar(
                        state = state.value,
                        onIntent = { intents += it },
                        modifier = Modifier.fillMaxWidth().testTag(BAR_TAG),
                        title = title,
                    )
                }
            }
        }
    }
}

/** The detail page in the app theme, with [state] changeable by the test and every intent recorded. */
internal class UsageDetailHarness(initial: UsageState) {
    val state: MutableState<UsageState> = mutableStateOf(initial)
    val intents = mutableListOf<UsageIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            UsageDetailScreen(route = DETAIL_ROUTE, state = state.value, onIntent = { intents += it })
        }
    }
}

internal fun ComposeContentTestRule.setUsageBar(state: UsageState, title: String? = null): UsageBarHarness {
    val harness = UsageBarHarness(state, title)
    setContent { harness.Content() }
    return harness
}

internal fun ComposeContentTestRule.setUsageDetail(state: UsageState): UsageDetailHarness {
    val harness = UsageDetailHarness(state)
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
