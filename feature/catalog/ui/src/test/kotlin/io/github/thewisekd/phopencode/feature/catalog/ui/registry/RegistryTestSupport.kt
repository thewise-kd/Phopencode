package io.github.thewisekd.phopencode.feature.catalog.ui.registry

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.api.Provider
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.AgentMode
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelLimit
import io.github.thewisekd.phopencode.core.model.ModelPriceTier
import io.github.thewisekd.phopencode.core.model.ModelPricing
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.PermissionEffect
import io.github.thewisekd.phopencode.core.model.PermissionRule
import io.github.thewisekd.phopencode.core.model.PriceThreshold
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogList
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryState
import java.math.BigDecimal

/** Made-up commands, agents and models: no real provider, key or path. */
internal val review = Command(name = "review", description = "Review the open changes")
internal val release = Command(name = "release")

internal val buildRules = listOf(
    PermissionRule(action = "edit", resource = "src/**", effect = PermissionEffect.Allow),
    PermissionRule(action = "bash", resource = "rm *", effect = PermissionEffect.Deny),
    PermissionRule(action = "webfetch", resource = "*", effect = PermissionEffect.Ask),
    PermissionRule(action = "doom", resource = "anything", effect = PermissionEffect.Unknown("maybe")),
)

internal val build = Agent(
    id = "build",
    name = "Build",
    description = "Writes and runs code",
    mode = AgentMode.Primary,
    model = ModelRef(providerId = "acme", modelId = "large"),
    permissions = buildRules,
)
internal val explore = Agent(id = "explore", name = "Explore", mode = AgentMode.Subagent)
internal val helper = Agent(id = "helper", name = "Helper", mode = AgentMode.All)
internal val odd = Agent(id = "odd", name = "Odd", mode = AgentMode.Unknown("sideways"), hidden = true)

private fun cost(text: String): Cost = Cost.of(BigDecimal(text))!!

internal val large = Model(
    id = "large",
    providerId = "acme",
    name = "Acme Large",
    limit = ModelLimit(context = 200_000, input = null, output = 64_000),
    pricing = ModelPricing(
        listOf(
            ModelPriceTier(input = cost("3"), output = cost("15"), cacheRead = cost("0.075"), cacheWrite = null),
            ModelPriceTier(
                threshold = PriceThreshold(type = "context", size = 200_000),
                input = cost("6"),
                output = cost("22.5"),
            ),
        ),
    ),
    variants = listOf("fast", "deep"),
)
internal val small = Model(id = "small", providerId = "acme", name = "Acme Small")
internal val stray = Model(id = "stray", providerId = "unlisted", name = "Stray")

internal val acme = Provider(id = "acme", name = "Acme AI")
internal val defaultModel = ModelRef(providerId = "acme", modelId = "large", variant = "fast")

internal val registry = RegistryState(
    commands = CatalogList(loading = false, items = listOf(review, release)),
    agents = CatalogList(loading = false, items = listOf(build, explore, helper, odd)),
    models = CatalogList(loading = false, items = listOf(large, small, stray)),
    providers = listOf(acme),
    defaultModel = defaultModel,
)

internal fun ComposeContentTestRule.setRegistry(state: RegistryState, onIntent: (RegistryIntent) -> Unit = {}) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface { RegistryScreen(route = CatalogRoute.Registry, state = state, onIntent = onIntent) }
        }
    }
}

internal fun ComposeContentTestRule.setCommand(state: CommandDetailState, onIntent: (CommandDetailIntent) -> Unit = {}) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface { CommandDetailScreen(route = CatalogRoute.CommandDetail(state.name), state = state, onIntent = onIntent) }
        }
    }
}

internal fun ComposeContentTestRule.setAgent(state: AgentDetailState, onIntent: (AgentDetailIntent) -> Unit = {}) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface { AgentDetailScreen(route = CatalogRoute.AgentDetail(state.agentId), state = state, onIntent = onIntent) }
        }
    }
}

internal fun ComposeContentTestRule.setModel(state: ModelDetailState, onIntent: (ModelDetailIntent) -> Unit = {}) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                ModelDetailScreen(
                    route = CatalogRoute.ModelDetail(state.providerId, state.modelId),
                    state = state,
                    onIntent = onIntent,
                )
            }
        }
    }
}

internal val commandShown = CommandDetailState(name = "review", loading = false, command = review)
internal val agentShown = AgentDetailState(agentId = "build", loading = false, agent = build)
internal val modelShown = ModelDetailState(
    providerId = "acme",
    modelId = "large",
    loading = false,
    model = large,
    providerName = "Acme AI",
    defaultModel = defaultModel,
)

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

/**
 * One composition whose content a test swaps, since a rule takes `setContent` once. Each swap starts
 * from fresh remembered state.
 */
internal class Stage(private val rule: ComposeContentTestRule) {
    private var content by mutableStateOf<@Composable () -> Unit>({})
    private var generation by mutableIntStateOf(0)

    init {
        rule.setContent {
            PhopencodeTheme(darkTheme = false) {
                Surface { key(generation) { content() } }
            }
        }
    }

    fun show(next: @Composable () -> Unit) {
        rule.runOnIdle {
            content = next
            generation++
        }
        rule.waitForIdle()
    }
}

/** How many nodes show exactly [text], inside merged rows too. */
internal fun ComposeContentTestRule.countText(text: String): Int =
    onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size
