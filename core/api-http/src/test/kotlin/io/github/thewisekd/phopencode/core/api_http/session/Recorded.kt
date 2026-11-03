package io.github.thewisekd.phopencode.core.api_http.session

import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.api_http.base.fixture
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One line of a flow's `exchanges.jsonl`: a recorded HTTP call. */
internal class Exchange(private val flow: String, private val line: JsonObject) {
    val label: String = line.getValue("label").jsonPrimitive.content
    val method: String = line.getValue("method").jsonPrimitive.content

    /** Path and query, exactly as the recorder sent them. */
    val target: String = line.getValue("path").jsonPrimitive.content
    val status: Int = checkNotNull(line.getValue("status").jsonPrimitive.longOrNull).toInt()

    /** The JSON body sent, or null for none. */
    val request: JsonElement? = line["request"]?.takeUnless { it is JsonNull }

    /** The recorded response body; empty for a 204. */
    val response: String = line["response"]
        ?.takeUnless { it is JsonNull }
        ?.let { fixture("$flow/${it.jsonPrimitive.content}") }
        .orEmpty()

    /** [target] without the `location[directory]` parameter the recorder added to list calls. */
    val targetWithoutLocation: String
        get() = target
            .replace(Regex("""[?&]location%5Bdirectory%5D=[^&]*"""), "")
            .let { if ('?' !in it && '&' in it) it.replaceFirst('&', '?') else it }
}

/** Every exchange of [flow] with [label], in recording order. */
internal fun exchanges(flow: String, label: String): List<Exchange> =
    fixture("$flow/exchanges.jsonl").lines()
        .filter { it.isNotBlank() }
        .map { Exchange(flow, ApiJson.parseToJsonElement(it).jsonObject) }
        .filter { it.label == label }
        .also { check(it.isNotEmpty()) { "no exchange $label in $flow" } }

/** The one exchange of [flow] with [label]. */
internal fun exchange(flow: String, label: String): Exchange = exchanges(flow, label).single()

/** Flows whose recording has a `session-create` call. */
internal val CREATE_FLOWS: List<String> = listOf(
    "auto-title", "commands", "compaction", "delivery", "diff-revert", "error", "form-fields",
    "form-question", "git-setup", "interrupt", "lifecycle", "permission", "reasoning", "retry", "subagent",
    "subagent-permission", "text", "tool",
)
