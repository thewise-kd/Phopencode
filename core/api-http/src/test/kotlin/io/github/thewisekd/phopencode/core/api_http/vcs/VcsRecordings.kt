package io.github.thewisekd.phopencode.core.api_http.vcs

import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.api_http.base.fixture
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One recorded call of a VCS route, from a flow's `exchanges.jsonl`. */
internal class VcsExchange(
    val flow: String,
    val label: String,
    val method: String,
    /** Path and query as sent, already encoded. */
    val target: String,
    val status: Int,
    private val response: String?,
) {
    val responseBody: String
        get() = fixture("$flow/${checkNotNull(response) { "$this has no body" }}")

    override fun toString(): String = "$flow/$label"
}

internal object VcsRecordings {
    private val exchanges: List<VcsExchange> by lazy {
        listOf("catalog", "git-setup").flatMap { flow ->
            fixture("$flow/exchanges.jsonl").lineSequence().filter { it.isNotBlank() }.map { line ->
                val o = ApiJson.parseToJsonElement(line).jsonObject
                VcsExchange(
                    flow = flow,
                    label = o.getValue("label").jsonPrimitive.content,
                    method = o.getValue("method").jsonPrimitive.content,
                    target = o.getValue("path").jsonPrimitive.content,
                    status = o.getValue("status").jsonPrimitive.int,
                    response = o["response"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
                )
            }.toList()
        }
    }

    fun get(flow: String, label: String): VcsExchange =
        exchanges.single { it.flow == flow && it.label == label }

    /** Every recorded read of a `/api/vcs` route, which this module must be able to send. */
    val reads: List<VcsExchange> by lazy {
        exchanges.filter { it.method == "GET" && it.target.startsWith("/api/vcs") }
    }
}
