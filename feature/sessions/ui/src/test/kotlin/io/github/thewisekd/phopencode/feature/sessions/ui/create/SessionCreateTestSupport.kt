package io.github.thewisekd.phopencode.feature.sessions.ui.create

import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.AgentMode
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateState

/** Made-up folders, agents and models: no real server layout, user name or provider account. */
internal const val FOLDER = "/work/sample"
internal const val RECENT_ONE = "/work/alpha"
internal const val RECENT_TWO = "/data/beta"
internal const val TYPED_TITLE = "Tidy the parser"

internal val build = Agent(id = "build", name = "Build", description = "Writes code", mode = AgentMode.Primary)
internal val plan = Agent(id = "plan", name = "Plan", mode = AgentMode.All)
internal val fast = Model(id = "fast-1", providerId = "acme", name = "Acme Fast")
internal val deep = Model(id = "deep-2", providerId = "zeta", name = "Zeta Deep")
internal val fastRef = ModelRef(providerId = fast.providerId, modelId = fast.id)
internal val deepRef = ModelRef(providerId = deep.providerId, modelId = deep.id)

internal val createRoute = SessionsRoute.Create(directory = FOLDER)

/** The catalog is loaded and a folder is typed: everything can be picked and the session created. */
internal val ready = SessionCreateState(
    loading = false,
    directory = FOLDER,
    recentFolders = listOf(RECENT_ONE, RECENT_TWO),
    agents = listOf(build, plan),
    models = listOf(fast, deep),
    defaultModel = deepRef,
)

internal fun ComposeContentTestRule.setCreateScreen(
    state: SessionCreateState,
    onIntent: (SessionCreateIntent) -> Unit = {},
    route: SessionsRoute.Create = createRoute,
) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                SessionCreateScreen(route = route, state = state, onIntent = onIntent)
            }
        }
    }
}

/** The smallest size of anything tappable, as `PhopencodeSpacing.touchTarget`. */
internal val CREATE_TOUCH_TARGET = 48.dp

/** Float rounding slack, the same as Compose's own `assertTouchHeightIsEqualTo`. */
private val TOUCH_SIZE_TOLERANCE = 0.5.dp

/** Asserts the node's touch bounds are at least [minimum] in both directions. */
internal fun SemanticsNodeInteraction.assertTouchAtLeast(minimum: Dp = CREATE_TOUCH_TARGET): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("Failed to check the touch size.")
    val bounds: Rect = node.touchBoundsInRoot
    val (width, height) = with(node.layoutInfo.density) { bounds.width.toDp() to bounds.height.toDp() }
    if (width < minimum - TOUCH_SIZE_TOLERANCE || height < minimum - TOUCH_SIZE_TOLERANCE) {
        throw AssertionError("Actual touch size is $width x $height, expected at least $minimum")
    }
    return this
}
