package io.github.thewisekd.phopencode.core.api_http.catalog

import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.api.McpServer
import io.github.thewisekd.phopencode.core.api.McpStatus
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
import io.github.thewisekd.phopencode.core.model.Skill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Every recorded catalog list, sent for the recorded folder and decoded with nothing the core types
 * hold left behind. Expected values are read from the recording's JSON tree, apart from the mapping
 * under test.
 */
class RecordedCatalogTest {
    private suspend fun <T> recorded(label: String, call: suspend (CatalogApi) -> T): T {
        val exchange = catalogExchange(label)
        val (result, request) = replying(exchange.body, exchange.status, call)
        assertEquals(exchange.method, request.method, "$label: method")
        assertEquals(exchange.target, request.target, "$label: target")
        return result
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.optionalText(name: String): String? = get(name)?.jsonPrimitive?.content

    private fun price(o: JsonObject?, name: String): Cost? = o?.get(name)?.jsonPrimitive?.content?.let(Cost::parse)

    @Test
    fun `skills keep id, name, description, path and the full content`() = runTest {
        val exchange = catalogExchange("skills")
        val expected = exchange.items.map {
            Skill(
                id = it.text("id"),
                name = it.text("name"),
                description = it.optionalText("description"),
                autoInvoke = it["autoinvoke"]?.jsonPrimitive?.boolean ?: false,
                path = it.text("path"),
                content = it.text("content"),
            )
        }
        val skills = recorded("skills") { it.skills(RECORDED_FOLDER) }
        assertEquals(expected, skills)
        assertEquals(listOf("opencode", "report", "browser"), skills.map { it.id })
        assertTrue(skills.all { it.content.length > 1_000 }, "the full text is kept")
    }

    @Test
    fun `commands keep name and description`() = runTest {
        assertEquals(
            listOf(
                Command("init", "guided AGENTS.md setup"),
                Command("review", "review changes [commit|branch|pr], defaults to uncommitted"),
                Command("goal", "keep working until an objective is met [pause|resume|clear]"),
            ),
            recorded("commands") { it.commands(RECORDED_FOLDER) },
        )
    }

    @Test
    fun `agents keep mode, hidden flag and permissions, hidden agents included`() = runTest {
        val exchange = catalogExchange("agents")
        val expected = exchange.items.map { o ->
            Agent(
                id = o.text("id"),
                name = o.text("name"),
                description = o.optionalText("description"),
                mode = when (o.text("mode")) {
                    "primary" -> AgentMode.Primary
                    "subagent" -> AgentMode.Subagent
                    else -> error("unexpected mode in the recording")
                },
                hidden = o.getValue("hidden").jsonPrimitive.boolean,
                model = o["model"]?.jsonObject?.let { ModelRef(it.text("providerID"), it.text("id"), it.optionalText("variant")) },
                permissions = o.getValue("permissions").jsonArray.map {
                    val rule = it.jsonObject
                    PermissionRule(
                        rule.text("action"),
                        rule.text("resource"),
                        when (rule.text("effect")) {
                            "allow" -> PermissionEffect.Allow
                            "deny" -> PermissionEffect.Deny
                            "ask" -> PermissionEffect.Ask
                            else -> error("unexpected effect in the recording")
                        },
                    )
                },
            )
        }
        val agents = recorded("agents") { it.agents(RECORDED_FOLDER) }
        assertEquals(expected, agents)
        assertEquals(listOf("build", "general", "explore", "compaction", "title", "summary", "plan"), agents.map { it.id })
        assertEquals(setOf("compaction", "title", "summary"), agents.filter { it.hidden }.map { it.id }.toSet())
        val build = agents.first()
        assertEquals("Build", build.name)
        assertEquals(AgentMode.Primary, build.mode)
        assertEquals(PermissionRule("read", "*.env", PermissionEffect.Ask), build.permissions[1])
    }

    @Test
    fun `models keep provider, name, limits, price tiers and variant IDs`() = runTest {
        val exchange = catalogExchange("models")
        val expected = exchange.items.map { o ->
            val limit = o.getValue("limit").jsonObject
            Model(
                id = o.text("id"),
                providerId = o.text("providerID"),
                name = o.text("name"),
                limit = ModelLimit(
                    context = limit["context"]?.jsonPrimitive?.long,
                    input = limit["input"]?.jsonPrimitive?.long,
                    output = limit["output"]?.jsonPrimitive?.long,
                ),
                pricing = ModelPricing(
                    o.getValue("cost").jsonArray.map {
                        val tier = it.jsonObject
                        val cache = tier["cache"]?.jsonObject
                        assertNull(tier["tier"], "the recording has no tiered price")
                        ModelPriceTier(
                            input = price(tier, "input")!!,
                            output = price(tier, "output")!!,
                            cacheRead = price(cache, "read"),
                            cacheWrite = price(cache, "write"),
                        )
                    },
                ),
                variants = o.getValue("variants").jsonArray.map { it.jsonObject.text("id") },
            )
        }
        val models = recorded("models") { it.models(RECORDED_FOLDER) }
        assertEquals(expected, models)
        assertEquals(10, models.size)
        val sim1 = models.single { it.id == "sim-1" }
        assertEquals("sim", sim1.providerId)
        assertEquals(ModelLimit(context = 100000, output = 8000), sim1.limit)
        assertEquals(
            ModelPriceTier(
                input = Cost.parse("1.5")!!,
                output = Cost.parse("6")!!,
                cacheRead = Cost.parse("0.15"),
                cacheWrite = Cost.parse("1.875"),
            ),
            sim1.pricing?.base,
        )
        assertEquals(listOf("low", "medium", "high"), sim1.variants)
        assertEquals(ModelLimit(200000, 160000, 32000), models.single { it.id == "big-pickle" }.limit)
    }

    @Test
    fun `the default model is a reference to it`() = runTest {
        assertEquals(
            ModelRef(providerId = "sim", modelId = "sim-1"),
            recorded("model-default") { it.defaultModel(RECORDED_FOLDER) },
        )
    }

    @Test
    fun `providers keep only ID and name`() = runTest {
        assertEquals(
            listOf(Provider("opencode", "OpenCode"), Provider("sim", "Simulated")),
            recorded("providers") { it.providers(RECORDED_FOLDER) },
        )
    }

    @Test
    fun `MCP servers take the nested status and keep the error`() = runTest {
        assertEquals(
            listOf(
                McpServer("fixture-broken", McpStatus.Failed, "Connection closed: MCP server process exited with code 1"),
                McpServer("fixture-off", McpStatus.Disabled),
                McpServer(
                    "fixture-remote",
                    McpStatus.Failed,
                    "ECONNREFUSED: Unable to connect. Is the computer able to access the url?",
                ),
            ),
            recorded("mcp") { it.mcpServers(RECORDED_FOLDER) },
        )
    }

    @Test
    fun `plugins take ID and state`() = runTest {
        val exchange = catalogExchange("plugins")
        val plugins = recorded("plugins") { it.plugins(RECORDED_FOLDER) }
        assertEquals(exchange.items.map { it.text("id") }, plugins.map { it.id })
        assertEquals(93, plugins.size)
        assertTrue(plugins.all { it.status == PluginStatus.Active && it.error == null })
        assertEquals("opencode.tool.input.repair", plugins.first().id)
    }
}
