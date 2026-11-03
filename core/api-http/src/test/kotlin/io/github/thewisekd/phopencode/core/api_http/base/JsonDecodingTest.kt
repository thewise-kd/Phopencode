package io.github.thewisekd.phopencode.core.api_http.base

import io.github.thewisekd.phopencode.core.api.PageCursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

class JsonDecodingTest {
    /** Knows one field of `/api/info`, which has six. */
    @Serializable
    private data class Info(val version: String)

    @Serializable
    private data class SessionId(val id: String)

    @Serializable
    private data class Running(val type: String)

    @Serializable
    private data class Kind(val kind: String = "plain")

    @Test
    fun `unknown fields are ignored`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = fixture("startup/http/01-info.json"))
            val info = t.transport.receive(ApiRequest(HttpMethod.GET, listOf("api", "info")), Info.serializer())
            assertEquals(Info("2.2.5"), info)
        }
    }

    @Test
    fun `a recorded session list decodes into a page`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = fixture("catalog/http/25-sessions.json"))
            val envelope = t.transport.receive(
                ApiRequest(HttpMethod.GET, listOf("api", "session")),
                PageEnvelope.serializer(SessionId.serializer()),
            )
            val page = envelope.toPage { it.id }

            assertTrue(page.items.isNotEmpty())
            assertTrue(page.items.all { it.startsWith("ses_") })
        }
    }

    @Test
    fun `a recorded message page keeps both cursors`() {
        val body = fixture("text/http/04-messages-page-1.json")
        val page = ApiJson.decodeFromString(PageEnvelope.serializer(SessionId.serializer()), body).toPage { it }

        assertTrue(page.previousCursor!!.value.isNotEmpty())
        assertTrue(page.nextCursor!!.value.isNotEmpty())
        assertTrue(page.previousCursor != page.nextCursor)
    }

    @Test
    fun `a page without a cursor ends the list`() {
        val page = ApiJson.decodeFromString(PageEnvelope.serializer(SessionId.serializer()), """{"data":[]}""")
            .toPage { it }
        assertNull(page.nextCursor)
        assertTrue(page.isEnd)
        val cursor = ApiJson.decodeFromString(
            PageEnvelope.serializer(SessionId.serializer()),
            """{"data":[{"id":"a"}],"cursor":{"next":"n"}}""",
        ).toPage { it }
        assertEquals(PageCursor("n"), cursor.nextCursor)
        assertNull(cursor.previousCursor)
    }

    @Test
    fun `the location wrapper unwraps to its data`() {
        val body = """{"location":{"directory":"/workspace/project"},"data":{"ses_1":{"type":"running"}}}"""
        val data = ApiJson.decodeFromString(
            DataEnvelope.serializer(MapSerializer(String.serializer(), Running.serializer())),
            body,
        ).data
        assertEquals(mapOf("ses_1" to Running("running")), data)
    }

    @Test
    fun `a bare list decodes without a wrapper`() {
        val body = fixture("catalog/http/11-projects.json")
        val list = ApiJson.decodeFromString(ListSerializer(SessionId.serializer()), body)
        assertTrue(list.isNotEmpty())
    }

    @Test
    fun `a null for a field with a default takes the default`() {
        assertEquals(Kind(), ApiJson.decodeFromString(Kind.serializer(), """{"kind":null}"""))
    }
}
