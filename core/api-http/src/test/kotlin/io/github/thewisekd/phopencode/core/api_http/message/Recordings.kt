package io.github.thewisekd.phopencode.core.api_http.message

import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.api_http.base.fixture
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One line of a flow's `exchanges.jsonl`. */
internal class Exchange(
    val flow: String,
    val label: String,
    val method: String,
    /** Path and query as sent, already encoded. */
    val target: String,
    val request: JsonElement?,
    val status: Int,
    val response: String?,
) {
    val responseBody: String
        get() = fixture("$flow/${checkNotNull(response) { "$this has no body" }}")

    /** The session ID, the third path segment. */
    val sessionId: String
        get() = target.substringBefore('?').split('/')[3]

    /** The path segments after the session ID, without the query. */
    val route: List<String>
        get() = target.substringBefore('?').split('/').drop(4)

    override fun toString(): String = "$flow/$label"
}

internal object Recordings {
    val flows: List<String> by lazy {
        ApiJson.parseToJsonElement(fixture("manifest.json")).jsonObject.getValue("flows").jsonObject.keys.toList()
    }

    val exchanges: List<Exchange> by lazy {
        flows.flatMap { flow ->
            fixture("$flow/exchanges.jsonl").lineSequence().filter { it.isNotBlank() }.map { line ->
                val o = ApiJson.parseToJsonElement(line).jsonObject
                Exchange(
                    flow = flow,
                    label = o.text("label"),
                    method = o.text("method"),
                    target = o.text("path"),
                    request = o["request"]?.takeUnless { it is JsonNull },
                    status = o.getValue("status").jsonPrimitive.int,
                    response = o["response"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
                )
            }.toList()
        }
    }

    /** Session routes of [MessageApi][io.github.thewisekd.phopencode.core.api.MessageApi]. */
    private fun isSessionRoute(e: Exchange, method: String, vararg route: String): Boolean =
        e.method == method && e.target.startsWith("/api/session/") && e.route == route.toList()

    val messagePages: List<Exchange> by lazy {
        exchanges.filter { isSessionRoute(it, "GET", "message") && it.status == 200 }
    }
    val prompts: List<Exchange> by lazy { exchanges.filter { isSessionRoute(it, "POST", "prompt") } }
    val commands: List<Exchange> by lazy { exchanges.filter { isSessionRoute(it, "POST", "command") } }
    val agentSwitches: List<Exchange> by lazy { exchanges.filter { isSessionRoute(it, "POST", "agent") } }
    val modelSwitches: List<Exchange> by lazy { exchanges.filter { isSessionRoute(it, "POST", "model") } }
    val diffs: List<Exchange> by lazy { exchanges.filter { isSessionRoute(it, "GET", "diff") } }
    val revertStages: List<Exchange> by lazy { exchanges.filter { isSessionRoute(it, "POST", "revert", "stage") } }
    val revertCommits: List<Exchange> by lazy { exchanges.filter { isSessionRoute(it, "POST", "revert", "commit") } }
    val revertClears: List<Exchange> by lazy { exchanges.filter { isSessionRoute(it, "DELETE", "revert") } }

    /** The query of [target], decoded, in order. */
    fun query(target: String): List<Pair<String, String>> {
        val raw = target.substringAfter('?', "")
        if (raw.isEmpty()) return emptyList()
        return raw.split('&').map { part ->
            val name = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            decode(name) to decode(value)
        }
    }

    private fun decode(text: String): String = java.net.URLDecoder.decode(text, Charsets.UTF_8)

    /** The recorded body as compact JSON, in the recorded key order. */
    fun compact(body: JsonElement): String = ApiJson.encodeToString(JsonElement.serializer(), body)

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
}
