package io.github.thewisekd.phopencode.feature.chat.ui.subagent

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
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentNode
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentStatus
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentTools
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The tags the harness puts on the badge's and header's modifier, as the chat puts its slot tag there. */
internal const val BADGE_TAG = "test:subagent-badge"
internal const val HEADER_TAG = "test:subagent-header"

internal val LIST_ROUTE = ChatRoute.Subagents(sessionId = "ses_1")

/** A made-up task title, with Turkish letters so a lost character would show. */
internal const val EXPLORE_TITLE = "Depoyu incele ve şemayı özetle"
internal const val REVIEW_TITLE = "Review the diff"
internal const val NESTED_TITLE = "Find the call sites"

internal val EXPLORE = SessionId("ses_2")
internal val REVIEW = SessionId("ses_3")
internal val NESTED = SessionId("ses_4")

/** A running subagent with a running one of its own, and a finished one: 3 in all, 2 running. */
internal val TREE = listOf(
    SubagentNode(
        sessionId = EXPLORE,
        title = EXPLORE_TITLE,
        agent = "explore",
        status = SubagentStatus.Running,
        duration = 125.seconds,
        usage = TokenUsage.of(input = 1_234, output = 567, reasoning = 89, cacheRead = 12_000, cacheWrite = 300),
        tools = SubagentTools(completed = 1_200, failed = 1, running = 1, current = "bash"),
        children = listOf(
            SubagentNode(
                sessionId = NESTED,
                title = NESTED_TITLE,
                agent = "general",
                status = SubagentStatus.Running,
                duration = 45.seconds,
            ),
        ),
    ),
    SubagentNode(
        sessionId = REVIEW,
        title = REVIEW_TITLE,
        agent = "general",
        status = SubagentStatus.Succeeded,
        duration = 63.minutes,
        usage = TokenUsage.of(input = 1),
        tools = SubagentTools(completed = 1),
    ),
)

/** A root session's loaded subagents. */
internal val LOADED = SubagentState(loading = false, title = "Main", agent = "build", subagents = TREE)

/** A subagent's own session: its parent is the root, and it has one subagent of its own. */
internal val CHILD = SubagentState(
    loading = false,
    title = EXPLORE_TITLE,
    agent = "explore",
    parentId = SessionId("ses_1"),
    parentIsRoot = true,
    subagents = TREE[0].children,
)

/** The badge in the app theme, placed in a row as the chat places it; every intent is recorded. */
internal class BadgeHarness(initial: SubagentState) {
    val state: MutableState<SubagentState> = mutableStateOf(initial)
    val intents = mutableListOf<SubagentIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                // Scrollable so tests can scroll a node into view first: at API 35+ the insets push content down.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SubagentBadge(state.value, { intents += it }, Modifier.testTag(BADGE_TAG))
                }
            }
        }
    }
}

/** The header in the app theme, placed full width as the child session places it. */
internal class HeaderHarness(initial: SubagentState) {
    val state: MutableState<SubagentState> = mutableStateOf(initial)
    val intents = mutableListOf<SubagentIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SubagentHeader(state.value, { intents += it }, Modifier.fillMaxWidth().testTag(HEADER_TAG))
                }
            }
        }
    }
}

/** The list page in the app theme. */
internal class ListHarness(initial: SubagentState) {
    val state: MutableState<SubagentState> = mutableStateOf(initial)
    val intents = mutableListOf<SubagentIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            SubagentListScreen(LIST_ROUTE, state.value, { intents += it })
        }
    }
}

internal fun ComposeContentTestRule.setBadge(state: SubagentState): BadgeHarness =
    BadgeHarness(state).also { harness -> setContent { harness.Content() } }

internal fun ComposeContentTestRule.setHeader(state: SubagentState): HeaderHarness =
    HeaderHarness(state).also { harness -> setContent { harness.Content() } }

internal fun ComposeContentTestRule.setList(state: SubagentState): ListHarness =
    ListHarness(state).also { harness -> setContent { harness.Content() } }

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
