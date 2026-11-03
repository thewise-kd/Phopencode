package io.github.thewisekd.phopencode.core.api_http.message

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.Prompt
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api_http.base.ApiRequest
import io.github.thewisekd.phopencode.core.api_http.base.HttpMethod
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.api_http.base.assertApiError
import io.github.thewisekd.phopencode.core.api_http.base.fixture
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.core.model.ToolContent
import io.github.thewisekd.phopencode.core.model.ToolStatus
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect

class MessageApiBehaviorTest {
    private val session = SessionId("ses_1")

    private suspend fun listOne(json: String): Message = TestServer().use { t ->
        t.enqueue(body = """{"data":[$json],"cursor":{}}""")
        HttpMessageApi(t.transport).list(session).items.single()
    }

    @Test
    fun `a recorded prompt answer becomes the inbox item`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = fixture("delivery/http/02-prompt.json"))
            val item = HttpMessageApi(t.transport).prompt(session, Prompt("Begin."))
            assertEquals(
                InboxItem(
                    id = MessageId("msg_00000000000000000000000063"),
                    sessionId = SessionId("ses_00000000000000000000000020"),
                    createdAt = Instant.fromEpochMilliseconds(1700000033991),
                    content = InboxContent.User("Begin."),
                    delivery = PromptDelivery.Steer,
                ),
                item,
            )
            assertEquals("/api/session/ses_1/prompt", t.server.takeRequest().target)
        }
    }

    @Test
    fun `every recorded prompt answer decodes, with its delivery`() = runTest {
        for (exchange in Recordings.prompts) {
            TestServer().use { t ->
                t.enqueue(body = exchange.responseBody)
                val item = HttpMessageApi(t.transport).prompt(SessionId(exchange.sessionId), Prompt("x"))
                assertEquals(SessionId(exchange.sessionId), item.sessionId, "$exchange")
                assertTrue(item.content is InboxContent.User, "$exchange")
                assertTrue(item.delivery != null, "$exchange")
            }
        }
    }

    @Test
    fun `inbox answers keep attachments, other types and unknown deliveries`() = runTest {
        val body = """{"data":{"id":"msg_1","sessionID":"ses_1","time":{"created":1},"type":"user","payload":{
            "text":"t","files":[{"data":"aGk=","mime":"text/plain","source":{"type":"uri","uri":"src/a.kt"}},
            {"data":"aGk=","mime":"text/plain","source":{"type":"inline"},"name":"pasted.txt"}],
            "agents":[{"name":"plan"}],"skills":[{"id":"report","name":"Report"}]},"delivery":"later"}}"""
        TestServer().use { t ->
            t.enqueue(body = body)
            t.enqueue(body = """{"data":{"id":"msg_2","sessionID":"ses_1","time":{"created":1},"type":"move","payload":{},"delivery":"queue"}}""")
            t.enqueue(body = """{"data":{"id":"msg_3","sessionID":"ses_1","time":{"created":1},"type":"synthetic","payload":{"text":"s"},"delivery":"queue"}}""")
            val api = HttpMessageApi(t.transport)

            val user = api.prompt(session, Prompt("t"))
            assertEquals(InboxContent.User("t", listOf("src/a.kt", "pasted.txt"), listOf("plan"), listOf("report")), user.content)
            assertNull(user.delivery)
            assertEquals(InboxContent.Unknown("move"), api.prompt(session, Prompt("t")).content)
            assertEquals(InboxContent.Synthetic("s"), api.prompt(session, Prompt("t")).content)
        }
    }

    @Test
    fun `server errors map to typed errors`() = runTest {
        TestServer().use { t ->
            t.enqueue(status = 404, body = fixture("startup/http/03-unknown-session.json"))
            assertApiError(ApiError.NotFound) { HttpMessageApi(t.transport).prompt(session, Prompt("hello")) }
            t.enqueue(status = 409, body = """{"_tag":"SessionBusyError","message":"Session is busy"}""")
            assertApiError(ApiError.Conflict("Session is busy")) { HttpMessageApi(t.transport).commitRevert(session) }
        }
    }

    @Test
    fun `a body that does not fit is MalformedResponse`() = runTest {
        TestServer().use { t ->
            val api = HttpMessageApi(t.transport)
            t.enqueue(body = """{"data":{"not":"a list"}}""")
            assertApiError(ApiError.MalformedResponse) { api.list(session) }
            t.enqueue(body = """{"data":[{"type":"user","text":"no id"}]}""")
            assertApiError(ApiError.MalformedResponse) { api.list(session) }
            t.enqueue(body = """{"data":{"id":"msg_1","type":"user","payload":{}}}""")
            assertApiError(ApiError.MalformedResponse) { api.prompt(session, Prompt("x")) }
            t.enqueue(body = """{"data":{"snapshot":"s"}}""")
            assertApiError(ApiError.MalformedResponse) { api.stageRevert(session, MessageId("msg_1")) }
            t.enqueue(body = """{"data":[{"file":"a"}]}""")
            assertApiError(ApiError.MalformedResponse) { api.turnDiff(session) }
        }
    }

    @Test
    fun `unknown message types, blocks and tool states stay visible as unknown`() = runTest {
        val unknown = listOne("""{"id":"msg_1","time":{"created":5},"type":"hologram","glow":true}""")
        assertEquals(Message.Unknown(MessageId("msg_1"), session, Instant.fromEpochMilliseconds(5), "hologram"), unknown)

        val assistant = assertIs<Message.Assistant>(
            listOne(
                """{"id":"msg_2","time":{"created":1},"type":"assistant","agent":"build","model":{"id":"m","providerID":"p"},
                "content":[{"type":"image","url":"x"},{"type":"tool","id":"c1","name":"x","state":{"status":"paused","input":{}},"time":{"created":1}},
                {"type":"tool","id":"c2","name":"x","state":{"status":"streaming","input":"{\"pa"},"time":{"created":1}},
                {"type":"tool","id":"c3","name":"x","state":{"status":"running","input":{},"metadata":{}},"time":{"created":1}},
                {"type":"tool","id":"c4","name":"x","state":{"status":"completed","input":{},"content":[{"type":"file","uri":"file:///a.png","mime":"image/png","name":"a.png"},{"type":"audio"}]},"time":{"created":1}}]}""",
            ),
        )
        assertEquals(AssistantBlock.Unknown("image"), assistant.blocks[0])
        val tools = assistant.blocks.drop(1).map { it as AssistantBlock.Tool }
        assertEquals(ToolStatus.Unknown("paused"), tools[0].status)
        assertEquals(ToolStatus.Streaming, tools[1].status)
        assertEquals(io.github.thewisekd.phopencode.core.model.DataValue.Text("{\"pa"), tools[1].input)
        assertEquals(ToolStatus.Running, tools[2].status)
        assertEquals(
            listOf(ToolContent.File("file:///a.png", "image/png", "a.png"), ToolContent.Unknown("audio")),
            tools[3].content,
        )
    }

    @Test
    fun `a known type whose shape changed is kept as unknown rather than failing the page`() = runTest {
        val odd = listOne("""{"id":"msg_1","time":{"created":1},"type":"user","text":{"rich":true}}""")
        assertEquals(Message.Unknown(MessageId("msg_1"), session, Instant.fromEpochMilliseconds(1), "user"), odd)

        val oddTime = listOne("""{"id":"msg_2","time":{"created":"yesterday"},"type":"user","text":"hi"}""")
        assertEquals(Message.User(MessageId("msg_2"), session, null, "hi"), oddTime)
    }

    @Test
    fun `the remaining message types decode`() = runTest {
        val t0 = Instant.fromEpochMilliseconds(1)
        assertEquals(
            Message.System(MessageId("m"), session, t0, "rules"),
            listOne("""{"id":"m","time":{"created":1},"type":"system","text":"rules"}"""),
        )
        assertEquals(
            Message.SkillInvoked(MessageId("m"), session, t0, "Report"),
            listOne("""{"id":"m","time":{"created":1},"type":"skill","skill":"report","name":"Report","text":"body"}"""),
        )
        assertEquals(
            Message.LocationSwitched(MessageId("m"), session, t0, "/srv/other"),
            listOne("""{"id":"m","time":{"created":1},"type":"location-switched","location":{"directory":"/srv/other"}}"""),
        )
        assertEquals(
            Message.ModelSwitched(MessageId("m"), session, t0, ModelRef("p", "b", "high"), ModelRef("p", "a")),
            listOne(
                """{"id":"m","time":{"created":1},"type":"model-switched","model":{"id":"b","providerID":"p","variant":"high"},
                "previous":{"id":"a","providerID":"p"}}""",
            ),
        )
        assertEquals(
            Message.Idle(MessageId("m"), session, t0, SessionOutcome.Unknown("paused")),
            listOne("""{"id":"m","time":{"created":1},"type":"idle","outcome":"paused"}"""),
        )
        assertEquals(
            Message.User(MessageId("m"), session, t0, "hi", listOf("notes.txt"), listOf("plan"), listOf("report")),
            listOne(
                """{"id":"m","time":{"created":1},"type":"user","text":"hi",
                "files":[{"data":"aGk=","mime":"text/plain","source":{"type":"uri","uri":"notes.txt"}}],
                "agents":[{"name":"plan"}],"skills":[{"id":"report","name":"Report"}]}""",
            ),
        )
    }

    @Test
    fun `costs keep their exact decimal text, and a missing cost is not free`() = runTest {
        val exact = assertIs<Message.Assistant>(
            listOne(
                """{"id":"m","time":{"created":1},"type":"assistant","agent":"a","model":{"id":"m","providerID":"p"},"content":[],
                "cost":0.1000000000000000055511151231257827,"tokens":{"input":12345678901234567890123,"output":1,"reasoning":0,"cache":{"read":0,"write":0}}}""",
            ),
        )
        assertEquals("0.1000000000000000055511151231257827", exact.usage!!.cost.toString())
        assertEquals("12345678901234567890123".toBigInteger(), exact.usage!!.input)

        val noCost = assertIs<Message.Assistant>(
            listOne(
                """{"id":"m","time":{"created":1},"type":"assistant","agent":"a","model":{"id":"m","providerID":"p"},"content":[],
                "tokens":{"input":3,"output":1,"reasoning":0,"cache":{"read":0,"write":0}}}""",
            ),
        )
        assertNull(noCost.usage!!.cost)
        assertTrue(noCost.usage!!.hasMissingCost)

        val absurd = assertIs<Message.Assistant>(
            listOne(
                """{"id":"m","time":{"created":1},"type":"assistant","agent":"a","model":{"id":"m","providerID":"p"},"content":[],
                "cost":1e999999999,"tokens":{"input":1e999999999,"output":-1,"reasoning":0,"cache":{"read":0,"write":0}}}""",
            ),
        )
        assertNull(absurd.usage, "counters that are not plain non-negative integers are not a report")
    }

    @Test
    fun `a turn diff and a staged revert map every field`() = runTest {
        TestServer().use { t ->
            val api = HttpMessageApi(t.transport)
            t.enqueue(
                body = """{"data":[{"file":"a.bin","patch":"","additions":0,"deletions":0,"status":"deleted"},
                {"file":"b","patch":"p","additions":1,"deletions":2,"status":"renamed","more":true},
                {"file":"c","patch":"p","additions":1,"deletions":0,"status":"copied"}]}""",
            )
            assertEquals(
                listOf(
                    FileDiff(VcsFile("a.bin", VcsFileStatus.Deleted, 0, 0), ""),
                    FileDiff(VcsFile("b", VcsFileStatus.Renamed, 1, 2), "p"),
                    FileDiff(VcsFile("c", VcsFileStatus.Unknown("copied"), 1, 0), "p"),
                ),
                api.turnDiff(session, from = MessageId("msg_1"), to = MessageId("msg_2"), context = 3),
            )
            assertEquals("/api/session/ses_1/diff?from=msg_1&to=msg_2&context=3", t.server.takeRequest().target)

            t.enqueue(body = fixture("diff-revert/http/14-revert-stage-files.json"))
            val revert = api.stageRevert(session, MessageId("msg_00000000000000000000000081"), files = true)
            assertEquals("000000000000000000000000000000000000000b", revert.snapshot)
            assertEquals(VcsFile("tracked.txt", VcsFileStatus.Modified, 0, 1), revert.files.single().file)
            assertTrue(revert.files.single().patch!!.contains("-gamma"))
        }
    }

    @Test
    fun `nothing a user typed reaches the log`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = fixture("delivery/http/02-prompt.json"))
            t.enqueue(status = 204)
            val api = HttpMessageApi(t.transport)
            api.prompt(session, Prompt("secret prompt text", files = listOf("private/path.txt")))
            api.command(session, "review", "secret command text")
            val lines = t.logger.lines.joinToString("\n")
            assertEquals(2, t.logger.lines.size)
            assertFalse(lines.contains("secret"))
            assertFalse(lines.contains("private"))
            assertFalse(lines.contains("Begin."))
        }
    }

    /**
     * A first call leaves a pooled connection; the next request on it is read whole and the server
     * then closes it unanswered. OkHttp resends such a request unless it is marked not repeatable.
     */
    private suspend fun TestServer.dropAfterReading() {
        enqueue(body = "{}")
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        enqueue(status = 204)
        transport.execute(ApiRequest(HttpMethod.GET, listOf("api", "info")))
    }

    @Test
    fun `calls that start work or change history are sent at most once`() = runTest {
        val once: Map<String, suspend HttpMessageApi.() -> Unit> = mapOf(
            "prompt" to { prompt(session, Prompt("hello")) },
            "command" to { command(session, "review") },
            "stageRevert" to { stageRevert(session, MessageId("msg_1")) },
            "commitRevert" to { commitRevert(session) },
            "cancelRevert" to { cancelRevert(session) },
        )
        for ((name, call) in once) {
            TestServer().use { t ->
                t.dropAfterReading()
                assertApiError(ApiError.Network) { HttpMessageApi(t.transport).call() }
                assertEquals(2, t.server.requestCount, name)
            }
        }
    }

    @Test
    fun `switching agent or model sets a value, so it may be resent`() = runTest {
        val repeatable: Map<String, suspend HttpMessageApi.() -> Unit> = mapOf(
            "switchAgent" to { switchAgent(session, "plan") },
            "switchModel" to { switchModel(session, ModelRef("p", "m")) },
        )
        for ((name, call) in repeatable) {
            TestServer().use { t ->
                t.dropAfterReading()
                HttpMessageApi(t.transport).call()
                assertEquals(3, t.server.requestCount, name)
            }
        }
    }

    @Test
    fun `IDs that could change the route are refused before sending`() = runTest {
        TestServer().use { t ->
            val api = HttpMessageApi(t.transport)
            assertIs<ApiError.BadRequest>(
                io.github.thewisekd.phopencode.core.api_http.base.apiError { api.prompt(SessionId(".."), Prompt("x")) },
            )
            assertIs<ApiError.BadRequest>(
                io.github.thewisekd.phopencode.core.api_http.base.apiError { api.list(SessionId("")) },
            )
            assertEquals(0, t.server.requestCount)
        }
    }
}
