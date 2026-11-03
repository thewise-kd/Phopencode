package io.github.thewisekd.phopencode.core.api_http.catalog

import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.api_http.base.fixture
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.RecordedRequest

/** The folder every recorded catalog call was made for. */
internal val RECORDED_FOLDER = Location("/workspace/project")

/** One recorded call of the `catalog` flow. */
internal class CatalogExchange(val label: String, val method: String, val target: String, val status: Int, val body: String) {
    /** The recorded `data`, parsed independently of the code under test. */
    val data: JsonElement
        get() = ApiJson.parseToJsonElement(body).jsonObject.getValue("data")

    val items: List<JsonObject>
        get() = (data as JsonArray).map { it.jsonObject }
}

internal fun catalogExchange(label: String): CatalogExchange =
    fixture("catalog/exchanges.jsonl").lineSequence()
        .filter { it.isNotBlank() }
        .map { ApiJson.parseToJsonElement(it).jsonObject }
        .single { it.getValue("label").jsonPrimitive.content == label }
        .let { line ->
            CatalogExchange(
                label = label,
                method = line.getValue("method").jsonPrimitive.content,
                target = line.getValue("path").jsonPrimitive.content,
                status = line.getValue("status").jsonPrimitive.content.toInt(),
                body = fixture("catalog/${line.getValue("response").jsonPrimitive.content}"),
            )
        }

/** Answers one call with [body] and returns what [call] gave and the request the server saw. */
internal suspend fun <T> replying(
    body: String,
    status: Int = 200,
    call: suspend (CatalogApi) -> T,
): Pair<T, RecordedRequest> = TestServer().use { t ->
    t.enqueue(status, body)
    val result = call(HttpCatalogApi(t.transport))
    result to t.server.takeRequest()
}

/** Like [replying], also returning the lines the transport logged. */
internal suspend fun <T> replyingLogged(body: String, call: suspend (CatalogApi) -> T): Pair<T, List<String>> =
    TestServer().use { t ->
        t.enqueue(200, body)
        val result = call(HttpCatalogApi(t.transport))
        result to t.logger.lines.toList()
    }

/** A `{location, data}` body around [data]. */
internal fun envelope(data: String): String = """{"location":{"directory":"/workspace/project"},"data":$data}"""
