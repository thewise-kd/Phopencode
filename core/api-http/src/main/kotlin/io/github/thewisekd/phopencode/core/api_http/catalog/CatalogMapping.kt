package io.github.thewisekd.phopencode.core.api_http.catalog

import io.github.thewisekd.phopencode.core.api.McpServer
import io.github.thewisekd.phopencode.core.api.McpStatus
import io.github.thewisekd.phopencode.core.api.Plugin
import io.github.thewisekd.phopencode.core.api.PluginStatus
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
import io.github.thewisekd.phopencode.core.model.Skill
import java.math.BigDecimal
import kotlinx.serialization.json.JsonPrimitive

/*
 * Wire to model. A shape that cannot be what the server promised fails the call with
 * ApiError.MalformedResponse, through the transport. A number that is no usable value reads as
 * missing, as the core types define it: a limit is then "none given", and a price unknown, never
 * free. A value that is merely new, such as an MCP status, is kept raw in the model's Unknown case.
 */

/**
 * Bounds on any JSON number read here, checked before any BigDecimal arithmetic. A scale far from
 * zero, such as that of `1e-100000000`, makes `setScale` or an exact conversion build a power of ten
 * with that many digits.
 */
private const val MAX_NUMBER_LENGTH = 100
private const val MAX_NUMBER_SCALE = 100

/** The number's value, or null when it is no number or outside the bounds above. Cheap for any input. */
private fun JsonPrimitive?.boundedDecimal(): BigDecimal? {
    if (this == null || isString || content.length > MAX_NUMBER_LENGTH) return null
    val number = content.toBigDecimalOrNull() ?: return null
    return number.takeIf { it.scale() in -MAX_NUMBER_SCALE..MAX_NUMBER_SCALE }
}

/** A whole number from zero to `Long.MAX_VALUE`, or null. */
private fun JsonPrimitive?.toCount(): Long? {
    val number = boundedDecimal() ?: return null
    if (number.signum() < 0) return null
    return try {
        number.longValueExact()
    } catch (_: ArithmeticException) {
        null
    }
}

/** A price: a [Cost] of zero or more, or null when it is unknown. */
private fun JsonPrimitive?.toPrice(): Cost? {
    val number = boundedDecimal() ?: return null
    if (number.signum() < 0) return null
    return Cost.of(number)
}

internal fun SkillWire.toModel() = Skill(
    id = id,
    name = name,
    description = description,
    autoInvoke = autoInvoke,
    path = path,
    content = content,
)

internal fun CommandWire.toModel() = Command(name = name, description = description)

internal fun AgentWire.toModel() = Agent(
    id = id,
    name = name,
    description = description,
    mode = when (mode) {
        "primary" -> AgentMode.Primary
        "subagent" -> AgentMode.Subagent
        "all" -> AgentMode.All
        else -> AgentMode.Unknown(mode)
    },
    hidden = hidden,
    model = model?.let { ModelRef(providerId = it.providerId, modelId = it.id, variant = it.variant) },
    permissions = permissions.map { it.toModel() },
)

private fun RuleWire.toModel() = PermissionRule(
    action = action,
    resource = resource,
    effect = when (effect) {
        "allow" -> PermissionEffect.Allow
        "deny" -> PermissionEffect.Deny
        "ask" -> PermissionEffect.Ask
        else -> PermissionEffect.Unknown(effect)
    },
)

internal fun ModelWire.toModel() = Model(
    id = id,
    providerId = providerId,
    name = name,
    limit = limit?.let { ModelLimit(context = it.context.toCount(), input = it.input.toCount(), output = it.output.toCount()) }
        ?: ModelLimit(),
    pricing = cost?.let(::pricingOf),
    variants = variants.map { it.id },
)

/** What [ModelWire.toModel] gives as the server's default model. */
internal fun ModelWire.toRef() = ModelRef(providerId = providerId, modelId = id)

/**
 * The model's price tiers, or null when none is listed or any is unreadable: dropping one tier would
 * price usage at another tier's rates.
 */
private fun pricingOf(tiers: List<PriceTierWire>): ModelPricing? {
    if (tiers.isEmpty()) return null
    return ModelPricing(tiers.map { it.toModel() ?: return null })
}

private fun PriceTierWire.toModel(): ModelPriceTier? {
    val threshold = tier?.let { PriceThreshold(type = it.type, size = it.size.toCount() ?: return null) }
    return ModelPriceTier(
        threshold = threshold,
        input = input.toPrice() ?: return null,
        output = output.toPrice() ?: return null,
        cacheRead = cache?.read.toPrice(),
        cacheWrite = cache?.write.toPrice(),
    )
}

internal fun ProviderWire.toModel() = Provider(id = id, name = name)

internal fun McpServerWire.toModel() = McpServer(
    name = name,
    status = when (val raw = status.status) {
        "connected" -> McpStatus.Connected
        "pending" -> McpStatus.Pending
        "disabled" -> McpStatus.Disabled
        "failed" -> McpStatus.Failed
        "needs_auth" -> McpStatus.NeedsAuth
        else -> McpStatus.Unknown(raw)
    },
    error = status.error,
)

internal fun PluginWire.toModel() = Plugin(
    id = id,
    status = when (val raw = state.status) {
        "active" -> PluginStatus.Active
        "failed" -> PluginStatus.Failed
        else -> PluginStatus.Unknown(raw)
    },
    error = state.error,
)
