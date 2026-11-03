package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LocationEncodingTest {
    private val project = Location("/workspace/project")

    @Test
    fun `the location query matches the recorded request byte for byte`() = runTest {
        // From catalog/exchanges.jsonl: "/api/agent?location%5Bdirectory%5D=%2Fworkspace%2Fproject".
        val recorded = Json.parseToJsonElement(fixture("catalog/exchanges.jsonl").lineSequence().first())
            .jsonObject.getValue("path").jsonPrimitive.content

        TestServer().use { t ->
            t.enqueue(body = "{}")
            t.transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "agent"), location = project))

            assertEquals(recorded, t.server.takeRequest().target)
        }
    }

    @Test
    fun `awkward folder names survive the round trip`() = runTest {
        val awkward = Location("/home/fixture/my project/a&b=c?d#e+f%20/çığ/[x]")
        TestServer().use { t ->
            t.enqueue(body = "{}")
            t.transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "session"), location = awkward))

            val url = t.server.takeRequest().url
            assertEquals(awkward.directory, url.queryParameter("location[directory]"))
            assertEquals(listOf("location[directory]"), url.queryParameterNames.toList())
        }
    }

    @Test
    fun `no location sends no location parameter`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = "{}")
            t.transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "session")))

            assertNull(t.server.takeRequest().url.queryParameter("location[directory]"))
        }
    }

    @Test
    fun `other query parameters keep their order next to the location`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = "{}")
            t.transport.execute(
                ApiRequest(
                    HttpMethod.GET,
                    listOf("api", "session"),
                    query = listOf("limit" to "20", "search" to "a b&c"),
                    location = project,
                ),
            )

            val url = t.server.takeRequest().url
            assertEquals(listOf("limit", "search", "location[directory]"), url.queryParameterNames.toList())
            assertEquals("a b&c", url.queryParameter("search"))
        }
    }

    @Test
    fun `a path segment cannot add segments or a query`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = "{}")
            t.transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "session", "../x/y?z=1")))

            val url = t.server.takeRequest().url
            assertEquals(listOf("api", "session", "../x/y?z=1"), url.pathSegments)
            assertEquals(0, url.querySize)
        }
    }

    @Serializable
    private data class CreateBody(val title: String, val location: LocationWire)

    @Test
    fun `the body form of a location matches the recorded request`() = runTest {
        // From compaction/exchanges.jsonl: {"title": "Compaction flow", "location": {"directory": "/workspace/project"}}.
        val recorded = Json.parseToJsonElement(fixture("compaction/exchanges.jsonl").lineSequence().first())
            .jsonObject.getValue("request")

        TestServer().use { t ->
            t.enqueue(body = "{}")
            val body = ApiJson.encodeToJsonElement(CreateBody.serializer(), CreateBody("Compaction flow", project.toWire()))
            t.transport.execute(ApiRequest(HttpMethod.POST, listOf("api", "session"), body = body))

            val sent = t.server.takeRequest()
            assertEquals(recorded, Json.parseToJsonElement(sent.body!!.utf8()))
            assertEquals("application/json; charset=utf-8", sent.headers["Content-Type"])
        }
    }

    @Serializable
    private data class Patch(val title: String? = null, val archived: Boolean? = null)

    @Test
    fun `a body with no value leaves out absent fields`() {
        val encoded = ApiJson.encodeToJsonElement(Patch.serializer(), Patch(archived = true)) as JsonObject
        assertFalse("title" in encoded)
    }
}
