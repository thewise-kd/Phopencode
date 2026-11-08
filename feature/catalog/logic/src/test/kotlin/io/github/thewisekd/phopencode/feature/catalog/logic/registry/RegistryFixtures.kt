package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.CatalogApi
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
import io.github.thewisekd.phopencode.core.testing.api.FakeApiControl
import io.github.thewisekd.phopencode.core.testing.api.FakeCatalogApi
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogConnection
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A [CatalogConnection] over one [FakeCatalogApi] whose generation a test moves by hand: a new
 * generation stands for a new connection, null for none.
 */
internal class TestCatalogConnection(
    val control: FakeApiControl = FakeApiControl(),
    var generation: Long? = 1,
) : CatalogConnection {
    val api = FakeCatalogApi(control)

    override val generations: Flow<Long?> = flow { emit(generation) }

    override suspend fun <T> call(block: suspend (catalog: CatalogApi, generation: Long) -> T): T {
        val current = generation ?: throw ApiException(ApiError.Closed)
        return block(api, current)
    }

    /** How many times the server was asked for [name], as `catalog.commands`. */
    fun count(name: String): Int = control.calls.count { it == name }
}

internal object Fixtures {
    fun usd(amount: String): Cost = requireNotNull(Cost.of(BigDecimal(amount)))

    val review = Command(name = "review", description = "Review the secret-plan.md changes")
    val init = Command(name = "init")

    val readSecret = PermissionRule(action = "read", resource = "/srv/private/secret.env", effect = PermissionEffect.Deny)
    val editAll = PermissionRule(action = "edit", resource = "*", effect = PermissionEffect.Ask)

    val build = Agent(
        id = "build",
        name = "Build",
        description = "Builds the private project",
        mode = AgentMode.Primary,
        model = ModelRef(providerId = "anthropic", modelId = "claude-large", variant = "high"),
        permissions = listOf(readSecret, editAll),
    )
    val plan = Agent(id = "plan", name = "Plan", mode = AgentMode.Primary)
    val title = Agent(id = "title", name = "Title", mode = AgentMode.Subagent, hidden = true)
    val compaction = Agent(id = "compaction", name = "Compaction", mode = AgentMode.Subagent, hidden = true)

    val large = Model(
        id = "claude-large",
        providerId = "anthropic",
        name = "Claude Large",
        limit = ModelLimit(context = 200_000, output = 64_000),
        pricing = ModelPricing(
            listOf(
                ModelPriceTier(input = usd("3"), output = usd("15"), cacheRead = usd("0.3")),
                ModelPriceTier(
                    threshold = PriceThreshold(type = "context", size = 200_000),
                    input = usd("6"),
                    output = usd("22.5"),
                ),
            ),
        ),
        variants = listOf("high", "max"),
    )
    val small = Model(id = "small", providerId = "local", name = "Small")
    val anthropic = Provider(id = "anthropic", name = "Anthropic")
    val defaultRef = ModelRef(providerId = "anthropic", modelId = "claude-large")

    fun fill(api: FakeCatalogApi) {
        api.setCommands(listOf(review, init))
        api.setAgents(listOf(build, title, plan, compaction))
        api.setModels(listOf(large, small))
        api.setProviders(listOf(anthropic))
        api.setDefaultModel(defaultRef)
    }
}
