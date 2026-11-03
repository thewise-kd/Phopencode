package io.github.thewisekd.phopencode.core.api_http.catalog

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.McpServer
import io.github.thewisekd.phopencode.core.api.McpStatus
import io.github.thewisekd.phopencode.core.api.Plugin
import io.github.thewisekd.phopencode.core.api.PluginStatus
import io.github.thewisekd.phopencode.core.api.Provider
import io.github.thewisekd.phopencode.core.api_http.base.assertApiError
import io.github.thewisekd.phopencode.core.model.AgentMode
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelLimit
import io.github.thewisekd.phopencode.core.model.ModelPriceTier
import io.github.thewisekd.phopencode.core.model.ModelPricing
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.PermissionEffect
import io.github.thewisekd.phopencode.core.model.PriceThreshold
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively

/** Edge cases the recordings do not show: secrets, hostile numbers, new values, errors and the folder. */
class CatalogDecodingTest {
    private suspend fun <T> decode(data: String, call: suspend (CatalogApi) -> T): T = replying(envelope(data), call = call).first

    private fun model(cost: String = """[{"input":1,"output":2,"cache":{"read":0.5,"write":3}}]""", limit: String = """{"context":1000,"output":100}""") =
        """[{"id":"m","modelID":"m","providerID":"p","name":"M","package":"x","capabilities":{},"variants":[],""" +
            """"time":{"released":0},"cost":$cost,"status":"active","enabled":true,"limit":$limit}]"""

    private fun fast(what: String, block: suspend () -> Unit) {
        assertTimeoutPreemptively(Duration.ofSeconds(FAST_SECONDS), { runBlocking { block() } }, what)
    }

    // --- Secrets ---

    @Test
    fun `secret-bearing fields reach neither the result nor the log`() = runTest {
        // Built at runtime, so that no literal looks like a credential to scripts/check_boundaries.py.
        fun marker(name: String) = listOf("test", "secret", name).joinToString("-")
        val settingsKey = marker("settings")
        val headerValue = marker("header")
        val bodyValue = marker("body")
        val variantKey = marker("variant")
        val requestKey = marker("request")
        val envValue = marker("env")
        val mcpHeader = marker("mcp")
        val oauthValue = marker("oauth")
        val pluginOption = marker("plugin")
        val providerKey = marker("provider")
        val providerHeader = marker("providerheader")
        val secrets = listOf(
            settingsKey, headerValue, bodyValue, variantKey, requestKey, envValue, mcpHeader, oauthValue,
            pluginOption, providerKey, providerHeader,
        )
        val models = """[{"id":"m","modelID":"m","providerID":"p","name":"M","package":"x",
            "settings":{"apiKey":"$settingsKey","baseURL":"https://example.invalid/v1"},
            "headers":{"Authorization":"Bearer $headerValue"},"body":{"token":"$bodyValue"},
            "capabilities":{},"variants":[{"id":"low","settings":{"apiKey":"$variantKey"},"headers":{"x":"$variantKey"}}],
            "time":{"released":0},"cost":[],"status":"active","enabled":true,"limit":{"context":1,"output":1}}]"""
        val providers = """[{"id":"p","name":"P","activation":"enabled","package":"x",
            "settings":{"apiKey":"$providerKey"},"headers":{"x-api-key":"$providerHeader"}}]"""
        val agents = """[{"id":"a","name":"A","mode":"primary","hidden":false,"permissions":[],
            "request":{"settings":{"apiKey":"$requestKey"},"headers":{"a":"$requestKey"},"body":{}}}]"""
        val mcp = """[{"name":"s","status":{"status":"connected"},"env":{"TOKEN":"$envValue"},
            "headers":{"Authorization":"$mcpHeader"},"oauth":{"clientSecret":"$oauthValue"}}]"""
        val plugins = """[{"id":"x","source":{"type":"npm"},"features":{},"state":{"status":"active"},
            "options":{"apiKey":"$pluginOption"}}]"""
        val defaultModel = models.trim().removePrefix("[").removeSuffix("]")
        val results = listOf(
            replyingLogged(envelope(models)) { it.models() },
            replyingLogged(envelope(defaultModel)) { it.defaultModel() },
            replyingLogged(envelope(providers)) { it.providers() },
            replyingLogged(envelope(agents)) { it.agents() },
            replyingLogged(envelope(mcp)) { it.mcpServers() },
            replyingLogged(envelope(plugins)) { it.plugins() },
        )
        results.forEach { (result, log) ->
            val shown = buildString {
                append(result.toString())
                (result as? List<*>)?.forEach {
                    when (it) {
                        is McpServer -> append(it.error)
                        is Plugin -> append(it.error)
                    }
                }
                log.forEach(::append)
            }
            secrets.forEach { assertFalse(it in shown, "$it leaked into $result or the log") }
            assertTrue(log.isNotEmpty(), "the call was logged")
        }
        assertEquals(listOf(Provider("p", "P")), results[2].first)
    }

    @Test
    fun `wire types declare no settings, headers, environment or credentials`() {
        val forbidden = setOf(
            "settings", "headers", "body", "request", "env", "environment", "apikey", "api_key", "key", "token",
            "secret", "password", "oauth", "auth", "credentials", "options", "url", "baseurl", "command",
        )
        val seen = mutableSetOf<String>()
        fun visit(descriptor: SerialDescriptor) {
            if (!seen.add(descriptor.serialName)) return
            descriptor.elementNames.forEach { name ->
                assertFalse(name.lowercase() in forbidden, "${descriptor.serialName}.$name")
            }
            descriptor.elementDescriptors.forEach(::visit)
        }
        CatalogWireTypes.all.forEach(::visit)
        assertTrue(seen.size > 10, "the wire types were not visited")
    }

    // --- Numbers ---

    @Test
    fun `a hostile limit reads as missing, fast`() {
        listOf("1e-100000000", "1e100000000", "1" + "0".repeat(10_000), "1.5", "-1", "9223372036854775808", "\"100\"").forEach { value ->
            fast(value) {
                val limit = decode(model(limit = """{"context":$value,"input":5,"output":100}""")) { it.models() }.single().limit
                assertEquals(ModelLimit(context = null, input = 5, output = 100), limit, value)
            }
        }
    }

    @Test
    fun `a hostile input or output price leaves the pricing unknown, fast`() {
        listOf("1e-100000000", "1e100000000", "1" + "0".repeat(10_000), "-1", "\"1\"", "null").forEach { value ->
            fast(value) {
                val cost = """[{"input":$value,"output":2,"cache":{"read":0,"write":0}}]"""
                assertNull(decode(model(cost = cost)) { it.models() }.single().pricing, "input $value")
                val output = """[{"input":1,"output":$value,"cache":{"read":0,"write":0}}]"""
                assertNull(decode(model(cost = output)) { it.models() }.single().pricing, "output $value")
            }
        }
    }

    @Test
    fun `a hostile cache price is unknown, not free, fast`() {
        listOf("1e-100000000", "1e100000000", "-0.5", "\"0\"").forEach { value ->
            fast(value) {
                val cost = """[{"input":1,"output":2,"cache":{"read":$value,"write":3}}]"""
                val tier = decode(model(cost = cost)) { it.models() }.single().pricing!!.tiers.single()
                assertNull(tier.cacheRead, value)
                assertEquals(Cost.parse("3"), tier.cacheWrite)
            }
        }
    }

    @Test
    fun `a hostile tier size leaves the pricing unknown, fast`() {
        listOf("1e-100000000", "1e100000000", "1.5", "-3").forEach { value ->
            fast(value) {
                val cost = """[{"input":1,"output":2,"cache":{"read":0,"write":0}},
                    {"tier":{"type":"context","size":$value},"input":2,"output":4,"cache":{"read":0,"write":0}}]"""
                assertNull(decode(model(cost = cost)) { it.models() }.single().pricing, value)
            }
        }
    }

    @Test
    fun `prices keep their exact text, and tiers their threshold`() = runTest {
        val cost = """[{"input":0.1,"output":0.30000000000000004,"cache":{"read":0,"write":0}},
            {"tier":{"type":"context","size":200000},"input":2.5,"output":15},
            {"tier":{"type":"tokens","size":5},"input":3,"output":20,"cache":{"read":1e-7,"write":2}}]"""
        val pricing = decode(model(cost = cost)) { it.models() }.single().pricing
        assertEquals(
            ModelPricing(
                listOf(
                    ModelPriceTier(null, Cost.parse("0.1")!!, Cost.parse("0.30000000000000004")!!, Cost.ZERO, Cost.ZERO),
                    ModelPriceTier(PriceThreshold("context", 200000), Cost.parse("2.5")!!, Cost.parse("15")!!),
                    ModelPriceTier(PriceThreshold("tokens", 5), Cost.parse("3")!!, Cost.parse("20")!!, Cost.parse("0.0000001"), Cost.parse("2")),
                ),
            ),
            pricing,
        )
    }

    @Test
    fun `an empty cost list is no pricing`() = runTest {
        assertNull(decode(model(cost = "[]")) { it.models() }.single().pricing)
    }

    @Test
    fun `a missing limit object or variant list costs nothing else`() = runTest {
        val bare = """[{"id":"m","providerID":"p","name":"M"}]"""
        assertEquals(listOf(Model("m", "p", "M")), decode(bare) { it.models() })
    }

    // --- New and missing values ---

    @Test
    fun `MCP statuses map from the nested status, new ones kept raw`() = runTest {
        val data = """[{"name":"a","status":{"status":"connected"}},{"name":"b","status":{"status":"pending"}},
            {"name":"c","status":{"status":"disabled"}},{"name":"d","status":{"status":"failed","error":"e1"}},
            {"name":"e","status":{"status":"needs_auth","error":"e2"}},{"name":"f","status":{"status":"sleeping"}}]"""
        assertEquals(
            listOf(
                McpServer("a", McpStatus.Connected),
                McpServer("b", McpStatus.Pending),
                McpServer("c", McpStatus.Disabled),
                McpServer("d", McpStatus.Failed, "e1"),
                McpServer("e", McpStatus.NeedsAuth, "e2"),
                McpServer("f", McpStatus.Unknown("sleeping")),
            ),
            decode(data) { it.mcpServers() },
        )
    }

    @Test
    fun `plugin states map, failed ones keep their error, and the ID is optional`() = runTest {
        val data = """[{"id":"a","source":{"type":"builtin"},"features":{},"state":{"status":"active"}},
            {"source":{"type":"npm"},"features":{},"state":{"status":"failed","error":"boom","ref":"pkg@1"}},
            {"id":"c","source":{"type":"npm"},"features":{},"state":{"status":"loading"}}]"""
        assertEquals(
            listOf(
                Plugin("a", PluginStatus.Active),
                Plugin(null, PluginStatus.Failed, "boom"),
                Plugin("c", PluginStatus.Unknown("loading")),
            ),
            decode(data) { it.plugins() },
        )
    }

    @Test
    fun `agent modes and effects that are new are kept raw, and the agent model is a reference`() = runTest {
        val data = """[{"id":"a","name":"A","mode":"all","hidden":true,"permissions":[]},
            {"id":"b","name":"B","mode":"background","hidden":false,"model":{"id":"m","providerID":"p","variant":"high"},
             "permissions":[{"action":"x","resource":"*","effect":"maybe"}]}]"""
        val agents = decode(data) { it.agents() }
        assertEquals(AgentMode.All, agents[0].mode)
        assertTrue(agents[0].hidden)
        assertEquals(AgentMode.Unknown("background"), agents[1].mode)
        assertEquals(ModelRef("p", "m", "high"), agents[1].model)
        assertEquals(PermissionEffect.Unknown("maybe"), agents[1].permissions.single().effect)
    }

    @Test
    fun `a skill without autoinvoke does not invoke itself, and one with it does`() = runTest {
        val data = """[{"id":"a","name":"A","path":"/p/a.md","content":"x"},
            {"id":"b","name":"B","description":"d","autoinvoke":true,"path":"/p/b.md","content":"y"}]"""
        val skills = decode(data) { it.skills() }
        assertFalse(skills[0].autoInvoke)
        assertNull(skills[0].description)
        assertTrue(skills[1].autoInvoke)
    }

    @Test
    fun `no default model is null`() = runTest {
        assertNull(decode("null") { it.defaultModel() })
    }

    @Test
    fun `a list without the location wrapper still decodes`() = runTest {
        val (commands, _) = replying("""{"data":[{"name":"x"}]}""") { it.commands() }
        assertEquals("x", commands.single().name)
    }

    // --- Requests and errors ---

    @Test
    fun `each list is a GET of its route, with the folder only when one is given`() = runTest {
        val calls: List<Pair<String, suspend (CatalogApi, Location?) -> Unit>> = listOf(
            "/api/skill" to { api, l -> api.skills(l) },
            "/api/command" to { api, l -> api.commands(l) },
            "/api/agent" to { api, l -> api.agents(l) },
            "/api/model" to { api, l -> api.models(l) },
            "/api/model/default" to { api, l -> api.defaultModel(l) },
            "/api/provider" to { api, l -> api.providers(l) },
            "/api/mcp" to { api, l -> api.mcpServers(l) },
            "/api/plugin" to { api, l -> api.plugins(l) },
        )
        calls.forEach { (path, call) ->
            val data = if (path == "/api/model/default") "null" else "[]"
            val (_, plain) = replying(envelope(data)) { call(it, null) }
            assertEquals("GET", plain.method)
            assertEquals(path, plain.target)
            val (_, located) = replying(envelope(data)) { call(it, Location("/srv/a b&c")) }
            assertEquals("$path?location%5Bdirectory%5D=%2Fsrv%2Fa%20b%26c", located.target)
        }
    }

    @Test
    fun `server errors are typed`() = runTest {
        assertApiError(ApiError.NotFound) {
            replying("""{"_tag":"AgentNotFoundError","message":"no"}""", status = 404) { it.agents() }
        }
        assertApiError(ApiError.Server(503, "starting")) {
            replying("""{"_tag":"ServiceUnavailableError","message":"starting"}""", status = 503) { it.models() }
        }
    }

    @Test
    fun `a body that does not fit is MalformedResponse`() = runTest {
        val cases: List<Pair<String, suspend (CatalogApi) -> Unit>> = listOf(
            "not json" to { api -> api.commands() },
            envelope("""[{"description":"no name"}]""") to { api -> api.commands() },
            envelope("""[{"name":"s","status":"failed"}]""") to { api -> api.mcpServers() },
            envelope("""[{"id":"x","source":{},"features":{}}]""") to { api -> api.plugins() },
            envelope("""[{"id":"m","providerID":"p"}]""") to { api -> api.models() },
            envelope("""{"name":"x"}""") to { api -> api.providers() },
        )
        cases.forEach { (body, call) ->
            assertApiError(ApiError.MalformedResponse) { replying(body) { call(it) } }
        }
    }

    private companion object {
        /** Generous for a busy Pi; scale arithmetic on these numbers takes minutes or runs out of memory. */
        const val FAST_SECONDS = 10L
    }
}
