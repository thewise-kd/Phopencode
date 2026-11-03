package io.github.thewisekd.phopencode.core.api_http.session

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.SessionUpdate
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.api_http.base.assertApiError
import io.github.thewisekd.phopencode.core.api_http.base.fixture
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.ForkBoundary
import io.github.thewisekd.phopencode.core.model.ForkBoundaryType
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionFork
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionOutcome
import io.github.thewisekd.phopencode.core.model.SessionRevert
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively

/** Recorded replies decode into the model types. */
class SessionDecodingTest {
    private val ses13 = SessionId("ses_00000000000000000000000013")

    private suspend fun <T> replying(body: String, status: Int = 200, call: suspend (HttpSessionApi) -> T): T =
        TestServer().use { t ->
            t.enqueue(status, body)
            call(HttpSessionApi(t.transport))
        }

    private fun sessionJson(fields: String): String =
        """{"data":{"id":"ses_1","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,""" +
            """"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/w"}$fields}}"""

    @Test
    fun `a recorded created session decodes in full`() = runTest {
        val session = replying(fixture("lifecycle/http/01-session-create.json")) { it.create() }
        assertEquals(
            Session(
                id = ses13,
                title = "Lifecycle flow",
                directory = "/workspace/project",
                projectId = "0000000000000000000000000000000000000007",
                createdAt = Instant.fromEpochMilliseconds(1700000026967),
                updatedAt = Instant.fromEpochMilliseconds(1700000026967),
                archived = false,
                usage = TokenUsage.of(cost = Cost.ZERO),
                metadata = mapOf("origin" to DataValue.Text("fixture")),
            ),
            session,
        )
        assertFalse(session.usage!!.hasMissingCost)
    }

    @Test
    fun `an archived session carries archived, outcome and metadata`() = runTest {
        val archived = replying(fixture("lifecycle/http/09-archived-get.json")) { it.get(ses13) }
        assertTrue(archived.archived)
        assertEquals(SessionOutcome.Succeeded, archived.outcome)
        assertEquals(
            mapOf("origin" to DataValue.Text("fixture"), "pinned" to DataValue.Bool(true)),
            archived.metadata,
        )
        assertEquals(Instant.fromEpochMilliseconds(1700000027234), archived.updatedAt)

        val unarchived = replying(fixture("lifecycle/http/13-unarchived-get.json")) { it.get(ses13) }
        assertFalse(unarchived.archived)
    }

    @Test
    fun `a recorded child list keeps parent, agent, usage and both cursors`() = runTest {
        val page = replying(fixture("subagent/http/04-children.json")) { it.list() }
        val child = page.items.single()
        assertEquals(SessionId("ses_00000000000000000000000006"), child.parentId)
        assertFalse(child.isRoot)
        assertEquals("general", child.agent)
        assertEquals(SessionOutcome.Succeeded, child.outcome)
        assertEquals(TokenUsage.of(input = 180, output = 21, cost = Cost.parse("0.000396")), child.usage)
        assertNotNull(page.previousCursor)
        assertNotNull(page.nextCursor)
        assertFalse(page.isEnd)
    }

    @Test
    fun `a recorded list keeps the server's order, models and exact costs`() = runTest {
        val page = replying(fixture("catalog/http/25-sessions.json")) { it.list() }
        assertEquals(
            listOf("ses_00000000000000000000000024", "ses_00000000000000000000000023", "ses_00000000000000000000000022"),
            page.items.map { it.id.value },
        )
        assertEquals(ModelRef(providerId = "sim", modelId = "sim-2", variant = "default"), page.items[0].model)
        assertEquals(Cost.parse("0.016739999999999998"), page.items[1].usage?.cost)
    }

    @Test
    fun `an empty page ends the list`() = runTest {
        val page = replying("""{"data":[],"cursor":{}}""") { it.list() }
        assertTrue(page.items.isEmpty())
        assertTrue(page.isEnd)
    }

    @Test
    fun `recorded forks carry their boundary`() = runTest {
        val all = replying(fixture("lifecycle/http/14-fork.json")) { it.fork(ses13) }
        assertEquals(
            SessionFork(ses13, ForkBoundary(ForkBoundaryType.Through, MessageId("msg_00000000000000000000000048"))),
            all.fork,
        )
        assertEquals(SessionId("ses_00000000000000000000000014"), all.id)
        val part = replying(fixture("lifecycle/http/15-fork-before.json")) {
            it.fork(ses13, MessageId("msg_00000000000000000000000046"))
        }
        assertEquals(
            SessionFork(ses13, ForkBoundary(ForkBoundaryType.Before, MessageId("msg_00000000000000000000000046"))),
            part.fork,
        )
    }

    @Test
    fun `a recorded staged revert decodes with its files`() = runTest {
        val session = replying(fixture("diff-revert/http/11-session-staged.json")) {
            it.get(SessionId("ses_00000000000000000000000022"))
        }
        val revert = assertNotNull(session.revert)
        assertEquals(MessageId("msg_00000000000000000000000078"), revert.messageId)
        assertEquals("000000000000000000000000000000000000000b", revert.snapshot)
        val file = revert.files.single()
        assertEquals(VcsFile("tracked.txt", VcsFileStatus.Modified, additions = 1, deletions = 2), file.file)
        assertTrue(file.patch!!.startsWith("diff --git a/tracked.txt b/tracked.txt\n"))
    }

    @Test
    fun `revert file statuses map, and an unknown one is kept raw`() = runTest {
        val files = ""","revert":{"messageID":"msg_1","files":[
            {"file":"a","patch":"","additions":1,"deletions":0,"status":"added"},
            {"file":"b","patch":"","additions":0,"deletions":1,"status":"deleted"},
            {"file":"c","status":"renamed"},{"file":"d","status":"untracked"},{"file":"e","status":"copied"}]}"""
        val revert = replying(sessionJson(files)) { it.get(SessionId("ses_1")) }.revert
        assertEquals(
            SessionRevert(
                MessageId("msg_1"),
                files = listOf(
                    FileDiff(VcsFile("a", VcsFileStatus.Added, 1, 0), ""),
                    FileDiff(VcsFile("b", VcsFileStatus.Deleted, 0, 1), ""),
                    FileDiff(VcsFile("c", VcsFileStatus.Renamed)),
                    FileDiff(VcsFile("d", VcsFileStatus.Untracked)),
                    FileDiff(VcsFile("e", VcsFileStatus.Unknown("copied"))),
                ),
            ),
            revert,
        )
    }

    @Test
    fun `unknown outcome and boundary types are kept raw`() = runTest {
        val session = replying(
            sessionJson(""","outcome":"paused","fork":{"sessionID":"ses_0","boundary":{"type":"after","messageID":"m"}}"""),
        ) { it.get(SessionId("ses_1")) }
        assertEquals(SessionOutcome.Unknown("paused"), session.outcome)
        assertEquals(ForkBoundaryType.Unknown("after"), session.fork?.boundary?.type)
        val failed = replying(sessionJson(""","outcome":"failed"""")) { it.get(SessionId("ses_1")) }
        assertEquals(SessionOutcome.Failed, failed.outcome)
        val interrupted = replying(sessionJson(""","outcome":"interrupted"""")) { it.get(SessionId("ses_1")) }
        assertEquals(SessionOutcome.Interrupted, interrupted.outcome)
    }

    @Test
    fun `metadata numbers keep their exact value, and nested values decode`() = runTest {
        val session = replying(
            sessionJson(""","metadata":{"n":0.1000000000000000055511151231257827,"l":[1,null,"x"],"o":{"b":false}}"""),
        ) { it.get(SessionId("ses_1")) }
        assertEquals(
            mapOf(
                "n" to DataValue.Num(BigDecimal("0.1000000000000000055511151231257827")),
                "l" to DataValue.Items(listOf(DataValue.Num(BigDecimal.ONE), DataValue.Null, DataValue.Text("x"))),
                "o" to DataValue.Fields(mapOf("b" to DataValue.Bool(false))),
            ),
            session.metadata,
        )
    }

    /** The parser has its own depth rules; the mapping must not add a stack limit of its own. */
    @Test
    fun `deeply nested metadata maps both ways without a stack overflow`() {
        val depth = 20_000
        var json: JsonElement = JsonArray(emptyList())
        repeat(depth - 1) { json = JsonArray(listOf(json)) }
        val fields = fieldsOf(JsonObject(mapOf("deep" to json)))
        var value = fields.getValue("deep")
        var levels = 0
        while (value is DataValue.Items && value.values.isNotEmpty()) {
            value = value.values.single()
            levels++
        }
        assertEquals(depth - 1, levels)
        // Walked by hand: comparing structures this deep would overflow the test's own stack.
        var back: JsonElement = fields.toJson().getValue("deep")
        var backLevels = 0
        while (back is JsonArray && back.isNotEmpty()) {
            back = back.single()
            backLevels++
        }
        assertEquals(depth - 1, backLevels)
    }

    @Test
    fun `nested metadata in a reply decodes`() = runTest {
        val depth = 100
        val nested = "[".repeat(depth) + "]".repeat(depth)
        val session = replying(sessionJson(""","metadata":{"deep":$nested}""")) { it.get(SessionId("ses_1")) }
        assertTrue(session.metadata.getValue("deep") is DataValue.Items)
    }

    @Test
    fun `token counts beyond Long and cost text survive exactly`() = runTest {
        val session = replying(
            """{"data":{"id":"ses_1","cost":1e-3,"tokens":{"input":123456789012345678901234567890,"output":1,""" +
                """"reasoning":2,"cache":{"read":3,"write":4}}}}""",
        ) { it.get(SessionId("ses_1")) }
        assertEquals(
            TokenUsage(
                input = BigInteger("123456789012345678901234567890"),
                output = BigInteger.ONE,
                reasoning = BigInteger.TWO,
                cacheRead = BigInteger("3"),
                cacheWrite = BigInteger("4"),
                cost = Cost.parse("0.001"),
            ),
            session.usage,
        )
    }

    @Test
    fun `a cost out of range is missing, not zero`() = runTest {
        val session = replying(
            """{"data":{"id":"ses_1","cost":1e999999,"tokens":{"input":1,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}""",
        ) { it.get(SessionId("ses_1")) }
        val usage = assertNotNull(session.usage)
        assertNull(usage.cost)
        assertTrue(usage.hasMissingCost)
    }

    @Test
    fun `a session with no usage report has none`() = runTest {
        val session = replying("""{"data":{"id":"ses_1"}}""") { it.get(SessionId("ses_1")) }
        assertEquals(Session(SessionId("ses_1")), session)
    }

    @Test
    fun `replies that do not fit are MalformedResponse`() = runTest {
        val bad = listOf(
            """{"data":{"title":"no id"}}""",
            """{"data":{"id":"ses_1","tokens":{"input":1.5,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}""",
            """{"data":{"id":"ses_1","tokens":{"input":-1,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}""",
            """{"data":{"id":"ses_1","tokens":{"input":"7","output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}""",
            """{"data":{"id":"ses_1","tokens":{"input":1e999999999,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}}""",
            """{"data":{"id":"ses_1","metadata":[1]}}""",
            """not json""",
        )
        bad.forEach { body ->
            assertApiError(ApiError.MalformedResponse) { replying(body) { it.get(SessionId("ses_1")) } }
        }
    }

    /**
     * Numbers whose exponent or length would make BigDecimal scale arithmetic build huge values. Every
     * place that reads a JSON number must refuse them at once, before any such arithmetic.
     */
    private val hostileNumbers = listOf(
        "1e-100000000",
        "1e100000000",
        "1E+2147483647",
        "1".repeat(100_000),
        "1".repeat(50_000) + "." + "1".repeat(50_000),
    )

    @Test
    fun `hostile numbers fail fast with MalformedResponse wherever a number is read`() {
        val tokens = """"cache":{"read":0,"write":0}"""
        val places: List<Pair<String, (String) -> String>> = listOf(
            "time.created" to { n -> """{"data":{"id":"ses_1","time":{"created":$n,"updated":1}}}""" },
            "time.updated" to { n -> """{"data":{"id":"ses_1","time":{"created":1,"updated":$n}}}""" },
            "tokens.input" to { n -> """{"data":{"id":"ses_1","tokens":{"input":$n,"output":0,"reasoning":0,$tokens}}}""" },
            "tokens.cache.write" to
                { n -> """{"data":{"id":"ses_1","tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":$n}}}}""" },
            "metadata" to { n -> """{"data":{"id":"ses_1","metadata":{"n":$n,"l":[$n]}}}""" },
        )
        places.forEach { (place, body) ->
            hostileNumbers.forEach { number ->
                fast("$place = ${number.take(20)}") {
                    assertApiError(ApiError.MalformedResponse) { replying(body(number)) { it.get(SessionId("ses_1")) } }
                }
            }
        }
        hostileNumbers.forEach { number ->
            fast("inbox time = ${number.take(20)}") {
                assertApiError(ApiError.MalformedResponse) {
                    replying(
                        """{"data":{"id":"m","sessionID":"s","type":"compaction","time":{"created":$number}}}""",
                    ) { it.compact(SessionId("ses_1")) }
                }
            }
        }
    }

    @Test
    fun `a hostile cost is a missing cost, and is quick`() {
        hostileNumbers.forEach { number ->
            fast("cost = ${number.take(20)}") {
                val usage = replying("""{"data":{"id":"ses_1","cost":$number}}""") { it.get(SessionId("ses_1")) }.usage
                assertNull(assertNotNull(usage).cost)
            }
        }
    }

    @Test
    fun `ordinary timestamps still decode, fractional ones rounded down`() = runTest {
        val session = replying("""{"data":{"id":"ses_1","time":{"created":1700000000000.9,"updated":1.7e12}}}""") {
            it.get(SessionId("ses_1"))
        }
        assertEquals(Instant.fromEpochMilliseconds(1700000000000), session.createdAt)
        assertEquals(Instant.fromEpochMilliseconds(1700000000000), session.updatedAt)
    }

    private fun fast(what: String, block: suspend () -> Unit) {
        assertTimeoutPreemptively(Duration.ofSeconds(FAST_SECONDS), { runBlocking { block() } }, what)
    }

    @Test
    fun `a recorded compaction reply is its inbox item`() = runTest {
        val item = replying(fixture("compaction/http/04-compact.json")) {
            it.compact(SessionId("ses_00000000000000000000000023"))
        }
        assertEquals(
            InboxItem(
                id = MessageId("msg_00000000000000000000000090"),
                sessionId = SessionId("ses_00000000000000000000000023"),
                createdAt = Instant.fromEpochMilliseconds(1700000046614),
                content = InboxContent.Compaction,
                delivery = PromptDelivery.Steer,
            ),
            item,
        )
        val queued = replying(
            """{"data":{"id":"msg_1","sessionID":"ses_1","time":{"created":1},"type":"compaction","payload":{},"delivery":"queue"}}""",
        ) { it.compact(SessionId("ses_1")) }
        assertEquals(PromptDelivery.Queue, queued.delivery)
        val unknown = replying(
            """{"data":{"id":"msg_1","sessionID":"ses_1","time":{"created":1},"type":"move","payload":{},"delivery":"later"}}""",
        ) { it.compact(SessionId("ses_1")) }
        assertEquals(InboxContent.Unknown("move"), unknown.content)
        assertNull(unknown.delivery)
    }

    @Test
    fun `stop reports whether anything was interrupted`() = runTest {
        assertTrue(replying(fixture("interrupt/http/06-interrupt.json")) { it.stop(SessionId("ses_1")) })
        assertFalse(replying("""{"interrupted":false}""") { it.stop(SessionId("ses_1")) })
    }

    @Test
    fun `recorded active statuses decode, and an unknown one is kept raw`() = runTest {
        assertEquals(emptyMap(), replying(fixture("interrupt/http/02-active-idle.json")) { it.activeStatuses() })
        assertEquals(
            mapOf<SessionId, SessionActivity>(SessionId("ses_00000000000000000000000016") to SessionActivity.Running),
            replying(fixture("interrupt/http/04-active-running.json")) { it.activeStatuses() },
        )
        assertEquals(
            mapOf<SessionId, SessionActivity>(
                SessionId("ses_00000000000000000000000006") to SessionActivity.Running,
                SessionId("ses_00000000000000000000000007") to SessionActivity.Running,
            ),
            replying(fixture("subagent/http/03-active-during-subagent.json")) { it.activeStatuses() },
        )
        assertEquals(
            mapOf<SessionId, SessionActivity>(SessionId("ses_1") to SessionActivity.Unknown("waiting")),
            replying("""{"data":{"ses_1":{"type":"waiting"}}}""") { it.activeStatuses() },
        )
    }

    @Test
    fun `delete and update accept 204, and the recorded second delete is NotFound`() = runTest {
        replying("", status = 204) { it.delete(ses13) }
        replying("", status = 204) { it.update(ses13, SessionUpdate(title = "t")) }
        val again = exchange("lifecycle", "delete-again")
        assertApiError(ApiError.NotFound) {
            replying(again.response, again.status) { it.delete(SessionId("ses_00000000000000000000000014")) }
        }
    }

    @Test
    fun `wire types declare no settings, headers or API keys`() {
        val forbidden = setOf("settings", "headers", "apikey", "apiKey", "api_key")
        val seen = mutableSetOf<String>()
        fun visit(descriptor: SerialDescriptor) {
            if (!seen.add(descriptor.serialName)) return
            descriptor.elementNames.forEach { name ->
                assertFalse(name.lowercase() in forbidden.map { it.lowercase() }, "${descriptor.serialName}.$name")
            }
            descriptor.elementDescriptors.forEach(::visit)
        }
        SessionWireTypes.all.forEach(::visit)
        assertTrue(seen.size > 5, "the wire types were not visited")
    }

    private companion object {
        /** Generous for a busy Pi; scale arithmetic on these numbers takes minutes or runs out of memory. */
        const val FAST_SECONDS = 10L
    }
}
