package io.github.thewisekd.phopencode.core.api_http.catalog

import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.McpServer
import io.github.thewisekd.phopencode.core.api.Plugin
import io.github.thewisekd.phopencode.core.api.Provider
import io.github.thewisekd.phopencode.core.api_http.base.ApiRequest
import io.github.thewisekd.phopencode.core.api_http.base.DataEnvelope
import io.github.thewisekd.phopencode.core.api_http.base.HttpMethod
import io.github.thewisekd.phopencode.core.api_http.base.HttpTransport
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.Skill
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable

/**
 * [CatalogApi] over the opencode 2.2 HTTP API. Every list is a `GET` of its route, as recorded from
 * opencode 2.2.5, with the folder in `location[directory]` when one is given; errors are mapped by
 * [HttpTransport]. A reply that does not fit the recorded shapes fails with
 * `ApiError.MalformedResponse`.
 *
 * | Call | Route |
 * |---|---|
 * | skills | `GET /api/skill` |
 * | commands | `GET /api/command` |
 * | agents | `GET /api/agent` |
 * | models | `GET /api/model` |
 * | defaultModel | `GET /api/model/default` |
 * | providers | `GET /api/provider` |
 * | mcpServers | `GET /api/mcp` |
 * | plugins | `GET /api/plugin` |
 *
 * `/api/provider` and `/api/model` send API keys in clear. The wire types declare none of the
 * secret-bearing fields, so they are never decoded, and the transport logs no body.
 *
 * It holds nothing but [transport], so closing the transport closes it.
 */
internal class HttpCatalogApi(private val transport: HttpTransport) : CatalogApi {
    override suspend fun skills(location: Location?): List<Skill> =
        list("skill", location, SkillWire.serializer()).map { it.toModel() }

    override suspend fun commands(location: Location?): List<Command> =
        list("command", location, CommandWire.serializer()).map { it.toModel() }

    override suspend fun agents(location: Location?): List<Agent> =
        list("agent", location, AgentWire.serializer()).map { it.toModel() }

    override suspend fun models(location: Location?): List<Model> =
        list("model", location, ModelWire.serializer()).map { it.toModel() }

    override suspend fun defaultModel(location: Location?): ModelRef? =
        transport.receive(get(location, "model", "default"), DEFAULT_MODEL).data?.toRef()

    override suspend fun providers(location: Location?): List<Provider> =
        list("provider", location, ProviderWire.serializer()).map { it.toModel() }

    override suspend fun mcpServers(location: Location?): List<McpServer> =
        list("mcp", location, McpServerWire.serializer()).map { it.toModel() }

    override suspend fun plugins(location: Location?): List<Plugin> =
        list("plugin", location, PluginWire.serializer()).map { it.toModel() }

    private suspend fun <T> list(route: String, location: Location?, item: KSerializer<T>): List<T> =
        transport.receive(get(location, route), DataEnvelope.serializer(ListSerializer(item))).data

    private fun get(location: Location?, vararg route: String) =
        ApiRequest(HttpMethod.GET, listOf("api", *route), location = location)

    override fun toString(): String = "HttpCatalogApi"

    private companion object {
        val DEFAULT_MODEL = DataEnvelope.serializer(ModelWire.serializer().nullable)
    }
}
