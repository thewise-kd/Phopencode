package io.github.thewisekd.phopencode.feature.sessions.ui.list

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionBadge
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionListState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionRow
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.time.Clock
import kotlin.time.Instant

/** Made-up sessions and folders: no real project, path or prompt. */
internal const val FOLDER = "/work/sample"
internal const val OTHER_FOLDER = "/work/other"

internal val first = SessionRow(
    id = SessionId("ses_1"),
    title = "Fix the login form",
    directory = FOLDER,
    status = SessionBadge.Running,
    usage = TokenUsage(input = BigInteger.valueOf(1_000), output = BigInteger.valueOf(234), cost = Cost.of(BigDecimal("0.12"))),
)

internal val second = SessionRow(
    id = SessionId("ses_2"),
    title = "Write release notes",
    directory = OTHER_FOLDER,
    status = SessionBadge.Idle,
)

internal val untitled = SessionRow(
    id = SessionId("ses_3"),
    title = null,
    directory = FOLDER,
    status = SessionBadge.Idle,
)

/** The moment the tests' screens take as now. */
internal val NOW: Instant = Instant.parse("2026-03-15T12:00:00Z")

internal object FixedClock : Clock {
    override fun now(): Instant = NOW
}

internal val listed = SessionListState(loading = false, rows = listOf(first, second), endReached = true)

internal fun ComposeContentTestRule.setSessionList(
    state: SessionListState,
    route: SessionsRoute.SessionList = SessionsRoute.SessionList(directory = state.directory),
    onOpenCatalog: (CatalogDestination) -> Unit = {},
    onIntent: (SessionListIntent) -> Unit = {},
) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            CompositionLocalProvider(LocalSessionListClock provides FixedClock) {
                Surface { SessionListScreen(route = route, state = state, onIntent = onIntent, onOpenCatalog = onOpenCatalog) }
            }
        }
    }
}

/**
 * Scrolls the list until a node matching [matcher] is in view. The list composes only the rows in
 * view, so on a short screen a row below it does not exist yet, and a plain `performScrollTo` on it
 * finds nothing. [useUnmergedTree] matches inside a row, for example its badge.
 */
internal fun ComposeContentTestRule.scrollListTo(matcher: SemanticsMatcher, useUnmergedTree: Boolean = false) {
    onNodeWithTag(SESSION_LIST_TAG, useUnmergedTree).performScrollToNode(matcher)
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
