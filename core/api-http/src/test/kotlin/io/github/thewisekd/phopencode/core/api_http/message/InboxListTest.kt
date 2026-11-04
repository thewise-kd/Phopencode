package io.github.thewisekd.phopencode.core.api_http.message

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api_http.base.ApiRequest
import io.github.thewisekd.phopencode.core.api_http.base.HttpMethod
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.api_http.base.apiError
import io.github.thewisekd.phopencode.core.api_http.base.assertApiError
import io.github.thewisekd.phopencode.core.api_http.base.fixture
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect

/** `GET /api/session/{id}/inbox`: the items handed to a session and not yet delivered. */
class InboxListTest {
    private val session = SessionId("ses_1")
    private val recorded = SessionId("ses_00000000000000000000000020")

    private suspend fun listed(body: String): List<InboxItem> = TestServer().use { t ->
        t.enqueue(body = body)
        HttpMessageApi(t.transport).inbox(session)
    }

    private fun recordedItem(n: Int, created: Long, text: String, delivery: PromptDelivery) = InboxItem(
        id = MessageId("msg_000000000000000000000000$n"),
        sessionId = recorded,
        createdAt = Instant.fromEpochMilliseconds(created),
        content = InboxContent.User(text),
        delivery = delivery,
    )

    @Test
    fun `the recorded inbox becomes its items in order, read with a GET`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = fixture("delivery/http/07-inbox.json"))
            val items = HttpMessageApi(t.transport).inbox(session)
            assertEquals(
                listOf(
                    recordedItem(64, 1700000034137, "Also do this now.", PromptDelivery.Steer),
                    recordedItem(65, 1700000034159, "Afterwards do this.", PromptDelivery.Queue),
                    recordedItem(66, 1700000034211, "Never mind this.", PromptDelivery.Queue),
                    recordedItem(67, 1700000034235, "Make this one urgent.", PromptDelivery.Queue),
                ),
                items,
            )
            val request = t.server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/session/ses_1/inbox", request.target)
        }
    }

    @Test
    fun `every recorded inbox list decodes`() = runTest {
        assertTrue(Recordings.inboxLists.size >= 2, "the delivery flow records two lists")
        for (exchange in Recordings.inboxLists) {
            TestServer().use { t ->
                t.enqueue(body = exchange.responseBody)
                val items = HttpMessageApi(t.transport).inbox(SessionId(exchange.sessionId))
                assertTrue(items.isNotEmpty(), "$exchange")
                assertTrue(items.all { it.sessionId.value == exchange.sessionId }, "$exchange")
                assertTrue(items.all { it.content is InboxContent.User && it.delivery != null }, "$exchange")
            }
        }
    }

    @Test
    fun `an empty inbox is an empty list`() = runTest {
        assertEquals(emptyList(), listed("""{"data":[]}"""))
    }

    @Test
    fun `every type of the spec decodes, and a move is kept as unknown`() = runTest {
        val items = listed(
            """{"data":[
            {"id":"msg_1","sessionID":"ses_1","time":{"created":1},"type":"synthetic",
             "payload":{"text":"shell output","description":"ran ls","metadata":{"k":1}},"delivery":"queue"},
            {"id":"msg_2","sessionID":"ses_1","time":{"created":2},"type":"compaction","payload":{"auto":true},
             "delivery":"steer"},
            {"id":"msg_3","sessionID":"ses_1","time":{"created":3},"type":"compaction","payload":true,
             "delivery":"steer"},
            {"id":"msg_4","sessionID":"ses_1","time":{"created":4},"type":"move",
             "payload":{"projectID":"p","location":{"directory":"/workspace/other"}},"delivery":"queue"},
            {"id":"msg_5","sessionID":"ses_1","time":{"created":5},"type":"user","payload":{"text":"t",
             "files":[{"data":"aGk=","mime":"text/plain","source":{"type":"uri","uri":"src/a.kt"}}],
             "agents":[{"name":"plan"}],"skills":[{"id":"report","name":"Report"}],"metadata":{}},"delivery":"steer"}
            ]}""",
        )
        assertEquals(
            listOf(
                InboxContent.Synthetic("shell output"),
                InboxContent.Compaction,
                InboxContent.Compaction,
                InboxContent.Unknown("move"),
                InboxContent.User("t", listOf("src/a.kt"), listOf("plan"), listOf("report")),
            ),
            items.map { it.content },
        )
        assertEquals(
            listOf(PromptDelivery.Queue, PromptDelivery.Steer, PromptDelivery.Steer, PromptDelivery.Queue, PromptDelivery.Steer),
            items.map { it.delivery },
        )
        assertEquals((1L..5L).map(Instant::fromEpochMilliseconds), items.map { it.createdAt })
    }

    @Test
    fun `unknown fields, types and deliveries are tolerated`() = runTest {
        val items = listed(
            """{"data":[
            {"id":"msg_1","time":{"created":1,"queued":2},"type":"hologram","payload":{"glow":true},
             "delivery":"later","priority":9},
            {"id":"msg_2","sessionID":"ses_1","type":"user","payload":{"text":"no time","extra":[1]}}
            ],"location":{"directory":"/workspace/project"},"next":null}""",
        )
        assertEquals(
            listOf(
                InboxItem(MessageId("msg_1"), session, Instant.fromEpochMilliseconds(1), InboxContent.Unknown("hologram"), null),
                InboxItem(MessageId("msg_2"), session, null, InboxContent.User("no time"), null),
            ),
            items,
        )
    }

    @Test
    fun `a known type whose payload changed shape is kept as unknown rather than failing the list`() = runTest {
        val items = listed(
            """{"data":[
            {"id":"msg_1","sessionID":"ses_1","time":{"created":1},"type":"user","payload":{"body":"moved"},"delivery":"queue"},
            {"id":"msg_2","sessionID":"ses_1","time":{"created":2},"type":"synthetic","payload":"text","delivery":"queue"},
            {"id":"msg_3","sessionID":"ses_1","time":{"created":3},"type":"user","delivery":"queue"},
            {"id":"msg_4","sessionID":"ses_1","time":{"created":4},"type":"user","payload":{"text":"fine"},"delivery":"queue"}
            ]}""",
        )
        assertEquals(
            listOf(
                InboxContent.Unknown("user"),
                InboxContent.Unknown("synthetic"),
                InboxContent.Unknown("user"),
                InboxContent.User("fine"),
            ),
            items.map { it.content },
        )
        assertEquals(listOf("msg_1", "msg_2", "msg_3", "msg_4"), items.map { it.id.value })
    }

    @Test
    fun `an answer that is not a list of items is MalformedResponse`() = runTest {
        for (
        body in listOf(
            """{"data":{"not":"a list"}}""",
            """{"items":[]}""",
            """{"data":[{"type":"user","payload":{"text":"no id"}}]}""",
            """{"data":[{"id":"msg_1","payload":{"text":"no type"}}]}""",
            """{"data":[null]}""",
            """{"data":["msg_1"]}""",
            """{"data":[{"id":"msg_1","type":"user","payload":{"text":"t"},"time":"yesterday"}]}""",
            "not json",
        )
        ) {
            TestServer().use { t ->
                t.enqueue(body = body)
                assertApiError(ApiError.MalformedResponse) { HttpMessageApi(t.transport).inbox(session) }
            }
        }
    }

    @Test
    fun `server errors map to typed errors`() = runTest {
        TestServer().use { t ->
            val api = HttpMessageApi(t.transport)
            t.enqueue(status = 404, body = fixture("startup/http/03-unknown-session.json"))
            assertApiError(ApiError.NotFound) { api.inbox(session) }
            t.enqueue(status = 401, body = """{"_tag":"UnauthorizedError","message":"Unauthorized"}""")
            assertApiError(ApiError.Unauthorized) { api.inbox(session) }
            t.enqueue(status = 400, body = """{"_tag":"InvalidRequestError","message":"Bad session"}""")
            assertIs<ApiError.BadRequest>(apiError { api.inbox(session) })
            t.enqueue(status = 503, body = "")
            assertIs<ApiError.Server>(apiError { api.inbox(session) })
        }
    }

    @Test
    fun `reading the inbox may be resent after a dropped connection`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = "{}")
            t.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            t.enqueue(body = """{"data":[]}""")
            t.transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "info")))
            assertEquals(emptyList(), HttpMessageApi(t.transport).inbox(session))
            assertEquals(3, t.server.requestCount)
        }
    }

    @Test
    fun `a session ID that could change the route is refused before sending`() = runTest {
        TestServer().use { t ->
            val api = HttpMessageApi(t.transport)
            assertIs<ApiError.BadRequest>(apiError { api.inbox(SessionId("..")) })
            assertIs<ApiError.BadRequest>(apiError { api.inbox(SessionId("")) })
            assertEquals(0, t.server.requestCount)
        }
    }

    @Test
    fun `no queued text reaches the log`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = fixture("delivery/http/07-inbox.json"))
            HttpMessageApi(t.transport).inbox(session)
            val lines = t.logger.lines.joinToString("\n")
            assertEquals(1, t.logger.lines.size)
            assertFalse(lines.contains("Also do this"))
            assertFalse(lines.contains("urgent"))
        }
    }
}
