package io.github.thewisekd.phopencode.core.api_http.session

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.CreateSessionRequest
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.PageCursor
import io.github.thewisekd.phopencode.core.api.SessionParentFilter
import io.github.thewisekd.phopencode.core.api.SessionQuery
import io.github.thewisekd.phopencode.core.api.SessionUpdate
import io.github.thewisekd.phopencode.core.api.SortOrder
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.api_http.base.assertApiError
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.PermissionEffect
import io.github.thewisekd.phopencode.core.model.PermissionRule
import io.github.thewisekd.phopencode.core.model.SessionId
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect

/** The requests [HttpSessionApi] sends, against the recorded ones in `core/testing` fixtures. */
class SessionRequestTest {
    private val lifecycleSession = SessionId("ses_00000000000000000000000013")

    private fun TestServer.answer(exchange: Exchange) {
        enqueue(exchange.status, exchange.response)
    }

    private fun RecordedRequest.json(): JsonElement? =
        body?.utf8()?.takeIf { it.isNotEmpty() }?.let(ApiJson::parseToJsonElement)

    /** Checks method, path, query and body against the recording. */
    private fun RecordedRequest.assertMatches(exchange: Exchange, target: String = exchange.target) {
        assertEquals(exchange.method, method, "${exchange.label}: method")
        assertEquals(target, this.target, "${exchange.label}: target")
        assertEquals(exchange.request, json(), "${exchange.label}: body")
    }

    private fun recordedCreate(request: JsonElement?): CreateSessionRequest {
        val body = checkNotNull(request).jsonObject
        return CreateSessionRequest(
            title = body["title"]?.jsonPrimitive?.content,
            location = body["location"]?.jsonObject?.get("directory")?.jsonPrimitive?.content?.let(::Location),
            metadata = body["metadata"]?.jsonObject?.mapValues { DataValue.Text(it.value.jsonPrimitive.content) },
        )
    }

    @Test
    fun `create sends every recorded create body`() = runTest {
        CREATE_FLOWS.flatMap { exchanges(it, "session-create") }.forEach { exchange ->
            TestServer().use { t ->
                t.answer(exchange)
                HttpSessionApi(t.transport).create(recordedCreate(exchange.request))
                t.server.takeRequest().assertMatches(exchange)
            }
        }
    }

    @Test
    fun `create sends parent, agent, model and permissions by their wire names`() = runTest {
        TestServer().use { t ->
            t.answer(exchange("lifecycle", "session-create"))
            HttpSessionApi(t.transport).create(
                CreateSessionRequest(
                    parentId = SessionId("ses_parent"),
                    agent = "build",
                    model = ModelRef(providerId = "sim", modelId = "sim-2", variant = "default"),
                    permissions = listOf(
                        PermissionRule("shell", "*", PermissionEffect.Ask),
                        PermissionRule("read", "*.md", PermissionEffect.Allow),
                        PermissionRule("edit", "*", PermissionEffect.Deny),
                        PermissionRule("x", "*", PermissionEffect.Unknown("later")),
                    ),
                ),
            )
            val expected = ApiJson.parseToJsonElement(
                """{"parentID":"ses_parent","agent":"build","model":{"id":"sim-2","providerID":"sim","variant":"default"},
                   "permissions":[{"action":"shell","resource":"*","effect":"ask"},
                   {"action":"read","resource":"*.md","effect":"allow"},{"action":"edit","resource":"*","effect":"deny"},
                   {"action":"x","resource":"*","effect":"later"}]}""",
            )
            assertEquals(expected, t.server.takeRequest().json())
        }
    }

    @Test
    fun `an empty create sends an empty object, and a model without variant leaves it out`() = runTest {
        TestServer().use { t ->
            val api = HttpSessionApi(t.transport)
            repeat(2) { t.answer(exchange("lifecycle", "session-create")) }
            api.create()
            assertEquals(JsonObject(emptyMap()), t.server.takeRequest().json())
            api.create(CreateSessionRequest(model = ModelRef("p", "m")))
            assertEquals(ApiJson.parseToJsonElement("""{"model":{"id":"m","providerID":"p"}}"""), t.server.takeRequest().json())
        }
    }

    @Test
    fun `metadata of every kind is sent as plain JSON`() = runTest {
        TestServer().use { t ->
            t.enqueue(204)
            val metadata = mapOf(
                "text" to DataValue.Text("a"),
                "number" to DataValue.Num(BigDecimal("1.50")),
                "flag" to DataValue.Bool(false),
                "none" to DataValue.Null,
                "list" to DataValue.Items(listOf(DataValue.Num(BigDecimal("2")), DataValue.Text("b"))),
                "nested" to DataValue.Fields(mapOf("k" to DataValue.Bool(true))),
            )
            HttpSessionApi(t.transport).update(lifecycleSession, SessionUpdate(metadata = metadata))
            val expected = ApiJson.parseToJsonElement(
                """{"metadata":{"text":"a","number":1.50,"flag":false,"none":null,"list":[2,"b"],"nested":{"k":true}}}""",
            )
            assertEquals(expected, t.server.takeRequest().json())
        }
    }

    @Test
    fun `update sends each recorded patch`() = runTest {
        val updates = mapOf(
            "rename" to SessionUpdate(title = "Renamed flow"),
            "metadata" to SessionUpdate(
                metadata = mapOf("origin" to DataValue.Text("fixture"), "pinned" to DataValue.Bool(true)),
            ),
            "permissions" to SessionUpdate(permissions = listOf(PermissionRule("shell", "*", PermissionEffect.Ask))),
            "archive" to SessionUpdate(archived = true),
            "unarchive" to SessionUpdate(archived = false),
        )
        updates.forEach { (label, update) ->
            TestServer().use { t ->
                val exchange = exchange("lifecycle", label)
                t.answer(exchange)
                HttpSessionApi(t.transport).update(lifecycleSession, update)
                t.server.takeRequest().assertMatches(exchange)
            }
        }
    }

    @Test
    fun `an update with nothing to change sends an empty object`() = runTest {
        TestServer().use { t ->
            t.enqueue(204)
            HttpSessionApi(t.transport).update(lifecycleSession, SessionUpdate())
            assertEquals(JsonObject(emptyMap()), t.server.takeRequest().json())
        }
    }

    @Test
    fun `get, delete, fork, stop, compact and activeStatuses send the recorded requests`() = runTest {
        val calls: List<Pair<Exchange, suspend (HttpSessionApi) -> Unit>> = listOf(
            exchange("lifecycle", "archived-get") to { it.get(lifecycleSession) },
            exchange("lifecycle", "delete-fork") to { it.delete(SessionId("ses_00000000000000000000000014")) },
            exchange("lifecycle", "fork") to { it.fork(lifecycleSession) },
            exchange("lifecycle", "fork-before") to {
                it.fork(lifecycleSession, before = MessageId("msg_00000000000000000000000046"))
            },
            exchange("interrupt", "interrupt") to { it.stop(SessionId("ses_00000000000000000000000016")) },
            exchanges("compaction", "compact").first() to { it.compact(SessionId("ses_00000000000000000000000023")) },
            exchange("interrupt", "active-running") to { it.activeStatuses() },
        )
        calls.forEach { (exchange, call) ->
            TestServer().use { t ->
                t.answer(exchange)
                call(HttpSessionApi(t.transport))
                t.server.takeRequest().assertMatches(exchange)
            }
        }
    }

    @Test
    fun `the child filter sends the recorded parentID query`() = runTest {
        TestServer().use { t ->
            val exchange = exchange("subagent", "children")
            t.answer(exchange)
            HttpSessionApi(t.transport).list(
                SessionQuery(parent = SessionParentFilter.ChildrenOf(SessionId("ses_00000000000000000000000006"))),
            )
            t.server.takeRequest().assertMatches(exchange)
        }
    }

    /**
     * The recorder also sent `location[directory]`, which [SessionQuery] has no field for: it filters
     * by folder with the spec's `directory` parameter instead. The rest must match exactly.
     */
    @Test
    fun `list sends the recorded queries`() = runTest {
        val lists = listOf(
            exchange("lifecycle", "list-archived") to SessionQuery(archived = true, limit = 5),
            exchange("lifecycle", "list-default") to SessionQuery(limit = 5),
            exchange("lifecycle", "list-search") to SessionQuery(search = "Renamed", limit = 5),
            exchange("catalog", "sessions") to SessionQuery(limit = 3, order = SortOrder.Descending),
        )
        lists.forEach { (exchange, query) ->
            TestServer().use { t ->
                t.answer(exchange)
                HttpSessionApi(t.transport).list(query)
                t.server.takeRequest().assertMatches(exchange, exchange.targetWithoutLocation)
            }
        }
    }

    @Test
    fun `list sends every filter by its spec name`() = runTest {
        TestServer().use { t ->
            repeat(2) { t.enqueue(body = """{"data":[]}""") }
            val api = HttpSessionApi(t.transport)
            api.list(
                SessionQuery(
                    order = SortOrder.Ascending,
                    parent = SessionParentFilter.RootsOnly,
                    archived = false,
                    directory = "/srv/project",
                    project = "project-1",
                    subpath = "app/src",
                ),
            )
            assertEquals(
                "/api/session?parentID=null&archived=false&order=asc&directory=%2Fsrv%2Fproject&project=project-1" +
                    "&subpath=app%2Fsrc",
                t.server.takeRequest().target,
            )
            api.list()
            assertEquals("/api/session", t.server.takeRequest().target)
        }
    }

    /** A cursor holds the query of its first page, as the recorded cursors show. */
    @Test
    fun `a list with a cursor sends only the limit and the cursor`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = """{"data":[]}""")
            HttpSessionApi(t.transport).list(
                SessionQuery(
                    limit = 2,
                    order = SortOrder.Ascending,
                    search = "x",
                    parent = SessionParentFilter.RootsOnly,
                    archived = true,
                    directory = "/srv/project",
                    cursor = PageCursor("abc=="),
                ),
            )
            assertEquals("/api/session?limit=2&cursor=abc%3D%3D", t.server.takeRequest().target)
        }
    }

    @Test
    fun `a session ID that would change the route is refused before sending`() = runTest {
        TestServer().use { t ->
            val api = HttpSessionApi(t.transport)
            listOf("", ".", "..", "%2e%2e").forEach { id ->
                assertApiError(ApiError.BadRequest("A path segment of the request is empty or a dot segment.")) {
                    api.get(SessionId(id))
                }
            }
            assertEquals(0, t.server.requestCount)
        }
    }

    @Test
    fun `an ID with a slash stays one path segment`() = runTest {
        TestServer().use { t ->
            t.enqueue(204)
            HttpSessionApi(t.transport).delete(SessionId("a/b?c"))
            assertEquals("/api/session/a%2Fb%3Fc", t.server.takeRequest().target)
        }
    }

    @Test
    fun `calls that must not reach the server twice are not repeatable`() {
        val id = SessionId("ses_1")
        assertFalse(SessionRoutes.create(CreateSessionRequest()).repeatable, "create")
        assertFalse(SessionRoutes.delete(id).repeatable, "delete")
        assertFalse(SessionRoutes.stop(id).repeatable, "stop")
        assertFalse(SessionRoutes.fork(id, null).repeatable, "fork")
        assertFalse(SessionRoutes.compact(id).repeatable, "compact")
        assertTrue(SessionRoutes.update(id, SessionUpdate(title = "t")).repeatable, "update")
        assertTrue(SessionRoutes.get(id).repeatable, "get")
        assertTrue(SessionRoutes.list(SessionQuery()).repeatable, "list")
        assertTrue(SessionRoutes.active().repeatable, "active")
        assertNull(SessionRoutes.stop(id).body, "stop sends no body")
    }

    @Test
    fun `a create on a dropped connection reaches the server once and fails with Network`() = runTest {
        TestServer().use { t ->
            // Leaves a pooled connection, which the server then closes after reading the create.
            t.enqueue(body = """{"data":{}}""")
            t.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            t.answer(exchange("lifecycle", "session-create"))
            val api = HttpSessionApi(t.transport)
            api.activeStatuses()

            assertApiError(ApiError.Network) { api.create(CreateSessionRequest(title = "once")) }
            assertEquals(2, t.server.requestCount)
        }
    }
}
