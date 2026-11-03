package io.github.thewisekd.phopencode.core.api_http.event.decode

import io.github.thewisekd.phopencode.core.api.EventDurable
import io.github.thewisekd.phopencode.core.api.InboxContent
import io.github.thewisekd.phopencode.core.api.InterruptReason
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.Cost
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.FieldCondition
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.ForkBoundary
import io.github.thewisekd.phopencode.core.model.ForkBoundaryType
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.FormOption
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionSource
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.core.model.TextFormat
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.model.ToolContent
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class EventDecoderTest {
    private val session = "ses_1"
    private val message = "msg_1"

    private fun envelope(type: String, data: String, extra: String = ""): String =
        """{"id":"evt_1","created":1700000000123,"type":"$type","data":$data$extra}"""

    private fun decode(type: String, data: String, extra: String = ""): ServerEvent =
        EventDecoder.decode(type = null, data = envelope(type, data, extra), id = null)

    // Unknown and malformed

    @Test
    fun `an unknown type becomes Unknown with its raw type and meta`() {
        val event = decode("brand.new.thing", """{"anything":[1,2,{"x":null}]}""")
        assertEquals(ServerEvent.Unknown("brand.new.thing", event.meta), event)
        assertEquals("evt_1", event.meta.id)
        assertEquals(Instant.fromEpochMilliseconds(1700000000123), event.meta.createdAt)
    }

    @Test
    fun `an unknown type with data that is not an object is still Unknown`() {
        val event = decode("brand.new.thing", "\"just text\"")
        assertIs<ServerEvent.Unknown>(event)
        assertEquals("brand.new.thing", event.rawType)
    }

    @Test
    fun `a body that is not JSON is malformed, under the SSE type and id`() {
        val result = EventDecoder.decodeResult(type = "session.text.delta", data = "not json {", id = "sse-7")
        assertEquals(DecodeResult.Malformed("session.text.delta", "sse-7"), result)
        assertEquals(
            ServerEvent.Unknown("session.text.delta", io.github.thewisekd.phopencode.core.api.EventMeta(id = "sse-7")),
            EventDecoder.decode(type = "session.text.delta", data = "not json {", id = "sse-7"),
        )
    }

    @Test
    fun `a body that is not an object and has no SSE type is malformed with an empty type`() {
        assertEquals(DecodeResult.Malformed("", null), EventDecoder.decodeResult(type = null, data = "[1,2]", id = null))
        assertEquals(DecodeResult.Malformed("", null), EventDecoder.decodeResult(type = null, data = "", id = null))
    }

    @Test
    fun `the envelope type and id win over the SSE values`() {
        val text = envelope("session.deleted", """{"sessionID":"$session"}""")
        val event = EventDecoder.decode(type = "session.created", data = text, id = "sse-id")
        assertIs<ServerEvent.SessionDeleted>(event)
        assertEquals("evt_1", event.meta.id)
    }

    @Test
    fun `the SSE type and id are used when the envelope has none`() {
        val event = EventDecoder.decode(type = "session.deleted", data = """{"data":{"sessionID":"$session"}}""", id = "sse-id")
        assertEquals(ServerEvent.SessionDeleted(SessionId(session), event.meta), event)
        assertEquals("sse-id", event.meta.id)
    }

    @Test
    fun `a known event missing a required field is malformed, not an exception`() {
        val text = envelope("session.text.delta", """{"sessionID":"$session","assistantMessageID":"$message","ordinal":0}""")
        assertEquals(DecodeResult.Malformed("session.text.delta", "evt_1"), EventDecoder.decodeResult(null, text, null))
        val event = EventDecoder.decode(null, text, null)
        assertIs<ServerEvent.Unknown>(event)
        assertEquals("session.text.delta", event.rawType)
        assertEquals("evt_1", event.meta.id)
    }

    @Test
    fun `a known event with a wrongly typed required field is malformed`() {
        val cases = listOf(
            "session.text.ended" to """{"sessionID":"$session","assistantMessageID":"$message","ordinal":"zero","text":"x"}""",
            "session.text.started" to """{"sessionID":"$session","assistantMessageID":"$message","ordinal":1.5}""",
            "session.deleted" to """{"sessionID":42}""",
            "session.deleted" to """{"sessionID":{"nested":"x"}}""",
            "session.tool.called" to """{"sessionID":"$session","id":null}""",
            "session.usage.updated" to """{"sessionID":"$session","cost":1}""",
            "session.agent.selected" to """{"sessionID":"$session"}""",
            "session.model.selected" to """{"sessionID":"$session","model":{"id":"m"}}""",
            "form.created" to """{"form":{"id":"frm_1"}}""",
            "permission.asked" to """{"id":"per_1","sessionID":"$session"}""",
            "session.inbox.enqueued" to """{"sessionID":"$session","inboxID":"msg_2"}""",
            "session.revert.staged" to """{"sessionID":"$session","revert":{}}""",
            "session.created" to "[]",
        )
        for ((type, data) in cases) {
            val result = EventDecoder.decodeResult(null, envelope(type, data), null)
            assertEquals(DecodeResult.Malformed(type, "evt_1"), result, "$type $data")
        }
    }

    @Test
    fun `a malformed result carries none of the body text`() {
        val marker = "PRIVATE-PROMPT-TEXT"
        val text = envelope(
            "session.text.delta",
            """{"sessionID":"$session","assistantMessageID":"$message","ordinal":"$marker","delta":"$marker"}""",
            extra = ""","metadata":{"note":"$marker"},"location":{"directory":"/$marker"}""",
        )
        val result = EventDecoder.decodeResult(null, text, null)
        assertIs<DecodeResult.Malformed>(result)
        assertFalse(marker in result.toString())
        assertFalse(marker in result.rawType)
        val event = EventDecoder.decode(null, text, null)
        assertIs<ServerEvent.Unknown>(event)
        assertFalse(marker in event.rawType)
        assertEquals(io.github.thewisekd.phopencode.core.api.EventMeta(id = "evt_1"), event.meta)
    }

    @Test
    fun `deeply nested data does not throw`() {
        val deep = "[".repeat(20_000) + "]".repeat(20_000)
        val text = envelope("session.tool.called", """{"sessionID":"$session","id":"call_1","input":$deep}""")
        val result = EventDecoder.decodeResult(null, text, null)
        assertTrue(result is DecodeResult.Decoded || result is DecodeResult.Malformed)
    }

    @Test
    fun `optional fields of the wrong type become null instead of failing the event`() {
        val event = decode(
            "session.step.started",
            """{"sessionID":"$session","assistantMessageID":7,"agent":{"x":1},"model":"m","snapshot":[],"started":"soon"}""",
        )
        assertEquals(ServerEvent.StepStarted(SessionId(session), meta = event.meta), event)
    }

    @Test
    fun `settings and keys in an uncovered event are not kept`() {
        val event = decode("provider.updated", """{"id":"p","settings":{"apiKey":"placeholder-key"},"headers":{"a":"b"}}""")
        assertEquals(ServerEvent.Unknown("provider.updated", event.meta), event)
        assertFalse("placeholder-key" in event.meta.metadata.toString())
    }

    // Delta and ended stay apart

    @Test
    fun `text delta and text ended are different types with different fields`() {
        val delta = decode("session.text.delta", """{"sessionID":"$session","assistantMessageID":"$message","ordinal":2,"delta":"Hel"}""")
        val ended = decode("session.text.ended", """{"sessionID":"$session","assistantMessageID":"$message","ordinal":2,"text":"Hello"}""")
        assertEquals(ServerEvent.TextDelta(SessionId(session), MessageId(message), 2, "Hel", delta.meta), delta)
        assertEquals(ServerEvent.TextEnded(SessionId(session), MessageId(message), 2, "Hello", ended.meta), ended)
        assertNotEquals<Any>(delta::class, ended::class)
    }

    @Test
    fun `a text delta is not taken for an ended text and the reverse`() {
        val deltaWithText = EventDecoder.decodeResult(
            null,
            envelope("session.text.delta", """{"sessionID":"$session","assistantMessageID":"$message","ordinal":0,"text":"x"}"""),
            null,
        )
        assertIs<DecodeResult.Malformed>(deltaWithText)
        val endedWithDelta = EventDecoder.decodeResult(
            null,
            envelope("session.text.ended", """{"sessionID":"$session","assistantMessageID":"$message","ordinal":0,"delta":"x"}"""),
            null,
        )
        assertIs<DecodeResult.Malformed>(endedWithDelta)
    }

    @Test
    fun `reasoning delta and reasoning ended are different types, and ended text may be empty`() {
        val delta = decode("session.reasoning.delta", """{"sessionID":"$session","assistantMessageID":"$message","ordinal":0,"delta":""}""")
        val ended = decode(
            "session.reasoning.ended",
            """{"sessionID":"$session","assistantMessageID":"$message","ordinal":0,"text":"","native":{"a":1}}""",
        )
        assertEquals(ServerEvent.ReasoningDelta(SessionId(session), MessageId(message), 0, "", delta.meta), delta)
        assertEquals(ServerEvent.ReasoningEnded(SessionId(session), MessageId(message), 0, "", ended.meta), ended)
    }

    @Test
    fun `compaction delta and compaction ended are different types`() {
        val delta = decode("session.compaction.delta", """{"sessionID":"$session","text":"## Obj"}""")
        val ended = decode(
            "session.compaction.ended",
            """{"sessionID":"$session","reason":"manual","model":{"id":"sim-1","providerID":"sim"},"text":"## Objective",""" +
                """"tokens":{"input":5200,"output":40,"reasoning":0,"cache":{"read":0,"write":0}},"cost":0.00804}""",
        )
        assertEquals(ServerEvent.CompactionDelta(SessionId(session), "## Obj", delta.meta), delta)
        assertEquals(
            ServerEvent.CompactionEnded(
                SessionId(session),
                reason = "manual",
                model = ModelRef(providerId = "sim", modelId = "sim-1"),
                text = "## Objective",
                usage = TokenUsage.of(input = 5200, output = 40, cost = Cost.parse("0.00804")),
                meta = ended.meta,
            ),
            ended,
        )
    }

    @Test
    fun `tool input delta decodes to its own type`() {
        val event = decode("session.tool.input.delta", """{"sessionID":"$session","assistantMessageID":"$message","id":"call_1","delta":"{\"a"}""")
        assertEquals(ServerEvent.ToolInputDelta(SessionId(session), MessageId(message), "call_1", "{\"a", event.meta), event)
    }

    // Usage and cost

    @Test
    fun `cost is read from the number's text, not through a double`() {
        val texts = listOf("0.30000000000000004", "0.1234567890123456789012345", "1e-7", "0", "12.50")
        for (text in texts) {
            val event = decode("session.usage.updated", """{"sessionID":"$session","cost":$text,"tokens":{"input":1}}""")
            assertIs<ServerEvent.UsageUpdated>(event)
            assertEquals(Cost.parse(text), event.usage.cost, text)
            assertEquals(BigDecimal(text).stripTrailingZeros().toPlainString(), event.usage.cost.toString(), text)
        }
    }

    @Test
    fun `usage maps every counter, including counts past Long`() {
        val event = decode(
            "session.usage.updated",
            """{"sessionID":"$session","cost":0.001908,"tokens":{"input":123456789012345678901234,"output":58,""" +
                """"reasoning":25,"cache":{"read":50,"write":3}}}""",
        )
        assertEquals(
            ServerEvent.UsageUpdated(
                SessionId(session),
                TokenUsage(
                    input = BigInteger("123456789012345678901234"),
                    output = BigInteger.valueOf(58),
                    reasoning = BigInteger.valueOf(25),
                    cacheRead = BigInteger.valueOf(50),
                    cacheWrite = BigInteger.valueOf(3),
                    cost = Cost.parse("0.001908"),
                ),
                event.meta,
            ),
            event,
        )
    }

    @Test
    fun `a missing or unreadable cost is missing, not zero`() {
        for (cost in listOf("", ""","cost":"0.5"""", ""","cost":null""", ""","cost":1e999999999""")) {
            val event = decode("session.usage.updated", """{"sessionID":"$session","tokens":{"input":1}$cost}""")
            assertIs<ServerEvent.UsageUpdated>(event)
            assertNull(event.usage.cost, cost)
            assertTrue(event.usage.hasMissingCost, cost)
        }
    }

    @Test
    fun `a step ended carries its own usage and a step failed may have none`() {
        val ended = decode(
            "session.step.ended",
            """{"sessionID":"$session","assistantMessageID":"$message","finish":"stop","rawFinish":"end_turn",""" +
                """"tokens":{"input":400,"output":8,"reasoning":0,"cache":{"read":0,"write":0}},"cost":0.000648,""" +
                """"snapshot":"snap","files":["a.txt",3,"b.txt"]}""",
        )
        assertEquals(
            ServerEvent.StepEnded(
                SessionId(session),
                MessageId(message),
                usage = TokenUsage.of(input = 400, output = 8, cost = Cost.parse("0.000648")),
                finishReason = "stop",
                rawFinish = "end_turn",
                snapshot = "snap",
                files = listOf("a.txt", "b.txt"),
                meta = ended.meta,
            ),
            ended,
        )
        val failed = decode(
            "session.step.failed",
            """{"sessionID":"$session","assistantMessageID":"$message","error":{"type":"provider.transport",""" +
                """"message":"lost","status":200,"response":{"body":"raw"},"retryAfterMs":1500},"snapshot":"s","files":[]}""",
        )
        assertEquals(
            ServerEvent.StepFailed(
                SessionId(session),
                MessageId(message),
                error = StructuredError("provider.transport", "lost", 200, "raw", 1500),
                snapshot = "s",
                meta = failed.meta,
            ),
            failed,
        )
    }

    // Meta

    @Test
    fun `the envelope's meta is decoded`() {
        val event = decode(
            "session.archived",
            """{"sessionID":"$session"}""",
            extra = ""","location":{"directory":"/workspace/project"},"metadata":{"instructions":{"initial":true}},""" +
                """"durable":{"aggregateID":"$session","seq":4,"version":1}""",
        )
        assertEquals("evt_1", event.meta.id)
        assertEquals(Instant.fromEpochMilliseconds(1700000000123), event.meta.createdAt)
        assertEquals(Location("/workspace/project"), event.meta.location)
        assertEquals(
            mapOf("instructions" to DataValue.Fields(mapOf("initial" to DataValue.Bool(true)))),
            event.meta.metadata,
        )
        assertEquals(EventDurable(session, 4, 1), event.meta.durable)
    }

    @Test
    fun `a server connected event has no time`() {
        val event = EventDecoder.decode(null, """{"id":"evt_0","type":"server.connected","data":{}}""", null)
        assertEquals(ServerEvent.Connected(io.github.thewisekd.phopencode.core.api.EventMeta(id = "evt_0")), event)
    }

    // Specific shapes

    @Test
    fun `session created is built from flat fields`() {
        val event = decode(
            "session.created",
            """{"sessionID":"$session","slug":"s","version":"2.2.5","projectID":"p","location":{"directory":"/w"},""" +
                """"subpath":"","title":"T","parentID":"ses_0","agent":"build","metadata":{"k":2}}""",
        )
        assertEquals(
            ServerEvent.SessionCreated(
                SessionId(session), "s", "2.2.5", "p", Location("/w"), "", "T", SessionId("ses_0"), "build",
                mapOf("k" to DataValue.Num(BigDecimal("2"))), event.meta,
            ),
            event,
        )
    }

    @Test
    fun `session forked keeps its parent and boundary`() {
        val event = decode(
            "session.forked",
            """{"sessionID":"$session","parentID":"ses_0","boundary":{"type":"before","messageID":"$message"},"instructions":{}}""",
        )
        assertEquals(
            ServerEvent.SessionForked(SessionId(session), SessionId("ses_0"), ForkBoundary(ForkBoundaryType.Before, MessageId(message)), event.meta),
            event,
        )
        val through = decode("session.forked", """{"sessionID":"$session","boundary":{"type":"through","messageID":"$message"}}""")
        assertEquals(ForkBoundaryType.Through, (through as ServerEvent.SessionForked).boundary?.type)
        val other = decode("session.forked", """{"sessionID":"$session","boundary":{"type":"sideways","messageID":"$message"}}""")
        assertEquals(ForkBoundaryType.Unknown("sideways"), (other as ServerEvent.SessionForked).boundary?.type)
    }

    @Test
    fun `execution failures and interrupts map their details`() {
        val failed = decode("session.execution.failed", """{"sessionID":"$session","error":{"type":"provider.no-route","message":"m"}}""")
        assertEquals(ServerEvent.ExecutionFailed(SessionId(session), StructuredError("provider.no-route", "m"), failed.meta), failed)
        val reasons = mapOf("user" to InterruptReason.User, "shutdown" to InterruptReason.Shutdown, "later" to InterruptReason.Unknown("later"))
        for ((wire, reason) in reasons) {
            val event = decode("session.execution.interrupted", """{"sessionID":"$session","reason":"$wire"}""")
            assertEquals(ServerEvent.ExecutionInterrupted(SessionId(session), reason, event.meta), event)
        }
    }

    @Test
    fun `retry scheduled keeps the attempt and time`() {
        val event = decode(
            "session.retry.scheduled",
            """{"sessionID":"$session","assistantMessageID":"$message","attempt":2,"at":1700000031480,"error":{"type":"provider.transport","message":"m","status":200}}""",
        )
        assertEquals(
            ServerEvent.RetryScheduled(
                SessionId(session), MessageId(message), 2, Instant.fromEpochMilliseconds(1700000031480),
                StructuredError("provider.transport", "m", 200), event.meta,
            ),
            event,
        )
    }

    @Test
    fun `step started maps the model`() {
        val event = decode(
            "session.step.started",
            """{"sessionID":"$session","agent":"build","model":{"id":"sim-1","providerID":"sim","variant":"high"},""" +
                """"assistantMessageID":"$message","snapshot":"s","started":1700000018008}""",
        )
        assertEquals(
            ServerEvent.StepStarted(
                SessionId(session), MessageId(message), "build", ModelRef("sim", "sim-1", "high"), "s",
                Instant.fromEpochMilliseconds(1700000018008), event.meta,
            ),
            event,
        )
    }

    @Test
    fun `tool events map their payloads`() {
        val started = decode("session.tool.input.started", """{"sessionID":"$session","assistantMessageID":"$message","id":"c","name":"shell"}""")
        assertEquals(ServerEvent.ToolInputStarted(SessionId(session), MessageId(message), "c", "shell", started.meta), started)
        val ended = decode("session.tool.input.ended", """{"sessionID":"$session","assistantMessageID":"$message","id":"c","text":"{\"a\":1}"}""")
        assertEquals(ServerEvent.ToolInputEnded(SessionId(session), MessageId(message), "c", "{\"a\":1}", ended.meta), ended)
        val called = decode(
            "session.tool.called",
            """{"sessionID":"$session","assistantMessageID":"$message","id":"c","input":{"command":"ls","n":[1,true,null]},"executed":false}""",
        )
        assertEquals(
            ServerEvent.ToolCalled(
                SessionId(session), MessageId(message), "c",
                DataValue.Fields(
                    mapOf(
                        "command" to DataValue.Text("ls"),
                        "n" to DataValue.Items(listOf(DataValue.Num(BigDecimal.ONE), DataValue.Bool(true), DataValue.Null)),
                    ),
                ),
                false, called.meta,
            ),
            called,
        )
        val progress = decode(
            "session.tool.progress",
            """{"sessionID":"$session","assistantMessageID":"$message","id":"c","metadata":{"sessionID":"ses_child","status":"running"}}""",
        )
        assertEquals(
            ServerEvent.ToolProgress(
                SessionId(session), MessageId(message), "c", SessionId("ses_child"), "running",
                mapOf("sessionID" to DataValue.Text("ses_child"), "status" to DataValue.Text("running")), progress.meta,
            ),
            progress,
        )
        val success = decode(
            "session.tool.success",
            """{"sessionID":"$session","assistantMessageID":"$message","id":"c","content":[{"type":"text","text":"one"},""" +
                """{"type":"file","uri":"file:///a","mime":"text/plain","name":"a"},{"type":"image"}],"metadata":{"exit":0},"executed":true}""",
        )
        assertEquals(
            ServerEvent.ToolSucceeded(
                SessionId(session), MessageId(message), "c",
                listOf(ToolContent.Text("one"), ToolContent.File("file:///a", "text/plain", "a"), ToolContent.Unknown("image")),
                mapOf("exit" to DataValue.Num(BigDecimal.ZERO)), true, success.meta,
            ),
            success,
        )
        val failed = decode(
            "session.tool.failed",
            """{"sessionID":"$session","assistantMessageID":"$message","id":"c","error":{"type":"tool.execution","message":"m"},"executed":false}""",
        )
        assertEquals(
            ServerEvent.ToolFailed(SessionId(session), MessageId(message), "c", StructuredError("tool.execution", "m"), false, failed.meta),
            failed,
        )
    }

    @Test
    fun `compaction started and failed map their fields`() {
        val started = decode("session.compaction.started", """{"sessionID":"$session","reason":"manual","inputID":"msg_9"}""")
        assertEquals(ServerEvent.CompactionStarted(SessionId(session), "manual", MessageId("msg_9"), started.meta), started)
        val failed = decode(
            "session.compaction.failed",
            """{"sessionID":"$session","reason":"manual","inputID":"msg_9","error":{"type":"compaction.failed","message":"m"},""" +
                """"cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}""",
        )
        assertEquals(
            ServerEvent.CompactionFailed(
                SessionId(session), "manual", StructuredError("compaction.failed", "m"), MessageId("msg_9"),
                TokenUsage.of(cost = Cost.ZERO), failed.meta,
            ),
            failed,
        )
    }

    @Test
    fun `revert events map the staged files`() {
        val staged = decode(
            "session.revert.staged",
            """{"sessionID":"$session","revert":{"messageID":"$message","snapshot":"s","files":[""" +
                """{"file":"tracked.txt","status":"modified","additions":0,"deletions":1,"patch":"diff"},""" +
                """{"file":"img.png","status":"added"},{"status":"deleted"}]}}""",
        )
        assertIs<ServerEvent.RevertStaged>(staged)
        assertEquals(MessageId(message), staged.revert.messageId)
        assertEquals("s", staged.revert.snapshot)
        assertEquals(
            listOf(
                FileDiff(VcsFile("tracked.txt", VcsFileStatus.Modified, 0, 1), "diff"),
                FileDiff(VcsFile("img.png", VcsFileStatus.Added), null),
            ),
            staged.revert.files,
        )
        val committed = decode("session.revert.committed", """{"sessionID":"$session","to":"$message"}""")
        assertEquals(ServerEvent.RevertCommitted(SessionId(session), MessageId(message), committed.meta), committed)
        val cleared = decode("session.revert.cleared", """{"sessionID":"$session"}""")
        assertEquals(ServerEvent.RevertCleared(SessionId(session), cleared.meta), cleared)
    }

    @Test
    fun `shell events read the shell object and the nested output`() {
        val started = decode("session.shell.started", """{"sessionID":"$session","shell":{"id":"sh_1","status":"running","command":"ls","pid":1}}""")
        assertEquals(ServerEvent.ShellStarted(SessionId(session), "sh_1", "ls", started.meta), started)
        val ended = decode(
            "session.shell.ended",
            """{"sessionID":"$session","shell":{"id":"sh_1","command":"ls","exit":3},"output":{"output":"a\n","cursor":2}}""",
        )
        assertEquals(ServerEvent.ShellEnded(SessionId(session), "sh_1", "ls", "a\n", 3, ended.meta), ended)
    }

    @Test
    fun `inbox events map the item and delivery`() {
        val enqueued = decode(
            "session.inbox.enqueued",
            """{"inboxID":"msg_2","sessionID":"$session","item":{"type":"user","payload":{"text":"hi",""" +
                """"files":[{"name":"a.png","mime":"image/png","data":"AAAA","source":{"type":"inline"}}],""" +
                """"agents":[{"name":"explore"}],"skills":[{"id":"k","name":"review"}]},"delivery":"queue"}}""",
        )
        assertIs<ServerEvent.InboxEnqueued>(enqueued)
        assertEquals(MessageId("msg_2"), enqueued.item.id)
        assertEquals(SessionId(session), enqueued.item.sessionId)
        assertNull(enqueued.item.createdAt)
        assertEquals(InboxContent.User("hi", listOf("a.png"), listOf("explore"), listOf("review")), enqueued.item.content)
        assertEquals(PromptDelivery.Queue, enqueued.item.delivery)

        val contents = mapOf(
            """{"type":"synthetic","payload":{"text":"out"}}""" to InboxContent.Synthetic("out"),
            """{"type":"compaction","payload":{}}""" to InboxContent.Compaction,
            """{"type":"move","payload":{"to":"x"}}""" to InboxContent.Unknown("move"),
        )
        for ((item, content) in contents) {
            val event = decode("session.inbox.enqueued", """{"inboxID":"msg_2","sessionID":"$session","item":$item}""")
            assertIs<ServerEvent.InboxEnqueued>(event)
            assertEquals(content, event.item.content)
            assertNull(event.item.delivery)
        }

        val delivered = decode("session.inbox.delivered", """{"sessionID":"$session","inboxID":"msg_2"}""")
        assertEquals(ServerEvent.InboxDelivered(SessionId(session), MessageId("msg_2"), delivered.meta), delivered)
        val cancelled = decode("session.inbox.cancelled", """{"sessionID":"$session","inboxID":"msg_2"}""")
        assertEquals(ServerEvent.InboxCancelled(SessionId(session), MessageId("msg_2"), cancelled.meta), cancelled)
        val changed = decode("session.inbox.delivery.changed", """{"sessionID":"$session","inboxID":"msg_2","delivery":"steer"}""")
        assertEquals(ServerEvent.InboxDeliveryChanged(SessionId(session), MessageId("msg_2"), PromptDelivery.Steer, changed.meta), changed)
        val odd = decode("session.inbox.delivery.changed", """{"sessionID":"$session","inboxID":"msg_2","delivery":"later"}""")
        assertNull((odd as ServerEvent.InboxDeliveryChanged).delivery)
    }

    @Test
    fun `agent and model selection map their values`() {
        val agent = decode("session.agent.selected", """{"sessionID":"$session","agent":"build","previous":"plan"}""")
        assertEquals(ServerEvent.AgentSelected(SessionId(session), "build", "plan", agent.meta), agent)
        val model = decode("session.model.selected", """{"sessionID":"$session","model":{"id":"m","providerID":"p"}}""")
        assertEquals(ServerEvent.ModelSelected(SessionId(session), ModelRef("p", "m"), model.meta), model)
    }

    @Test
    fun `permission events map the request and the reply`() {
        val asked = decode(
            "permission.asked",
            """{"id":"per_1","sessionID":"$session","action":"read","resources":[".env"],"save":["*"],""" +
                """"source":{"type":"tool","messageID":"$message","id":"call_1"}}""",
        )
        assertIs<ServerEvent.PermissionAsked>(asked)
        assertEquals("per_1", asked.request.id.value)
        assertEquals(SessionId(session), asked.sessionId)
        assertEquals("read", asked.request.action)
        assertEquals(listOf(".env"), asked.request.resources)
        assertEquals(listOf("*"), asked.request.save)
        assertEquals(PermissionSource("tool", MessageId(message), "call_1"), asked.request.source)

        val replies = mapOf("once" to PermissionDecision.Once, "always" to PermissionDecision.Always, "reject" to PermissionDecision.Reject, "maybe" to null)
        for ((wire, decision) in replies) {
            val event = decode("permission.replied", """{"sessionID":"$session","requestID":"per_1","reply":"$wire"}""")
            assertIs<ServerEvent.PermissionReplied>(event)
            assertEquals("per_1", event.requestId.value)
            assertEquals(decision, event.decision)
        }
    }

    @Test
    fun `form replied and cancelled map their ids and answers`() {
        val replied = decode("form.replied", """{"id":"frm_1","sessionID":"$session","answer":{"q0":"Red","q1":["S"]}}""")
        assertIs<ServerEvent.FormReplied>(replied)
        assertEquals("frm_1", replied.formId.value)
        assertEquals(
            mapOf("q0" to DataValue.Text("Red"), "q1" to DataValue.Items(listOf(DataValue.Text("S")))),
            replied.answer,
        )
        val cancelled = decode("form.cancelled", """{"id":"frm_1","sessionID":"$session"}""")
        assertIs<ServerEvent.FormCancelled>(cancelled)
        assertEquals("frm_1", cancelled.formId.value)
    }

    @Test
    fun `form created maps every field type`() {
        val fields = listOf(
            """{"key":"name","title":"Name","description":"Your name","required":true,"type":"string","minLength":1,"maxLength":20,""" +
                """"pattern":"^[A-Za-z]+$","placeholder":"Ada","default":"Ada"}""",
            """{"key":"mail","title":"Mail","type":"string","format":"email"}""",
            """{"key":"plan","title":"Plan","type":"string","options":[{"value":"free","label":"Free"},{"value":"pro","label":"Pro","description":"More"}],"custom":true}""",
            """{"key":"seats","title":"Seats","when":[{"key":"plan","op":"eq","value":"pro"}],"type":"integer","minimum":1,"maximum":10,"default":2}""",
            """{"key":"ratio","when":[{"key":"plan","op":"neq","value":"free"},{"key":"plan","op":"gt","value":1}],"type":"number","minimum":"-Infinity","default":0.5}""",
            """{"key":"agree","title":"Agree","type":"boolean","default":false}""",
            """{"key":"extras","type":"multiselect","options":[{"value":"a","label":"A"}],"minItems":1,"maxItems":2,"default":["a"]}""",
            """{"key":"hidden","hidden":true,"type":"string","default":"x"}""",
            """{"key":"login","type":"external","url":"https://example.invalid/login","title":"Sign in","description":"Opens a page"}""",
            """{"key":"pic","type":"image"}""",
        )
        val event = decode(
            "form.created",
            """{"form":{"id":"frm_1","sessionID":"$session","title":"Setup","metadata":{"kind":"setup"},"fields":[${fields.joinToString(",")}]}}""",
        )
        assertIs<ServerEvent.FormCreated>(event)
        val form = event.form
        assertEquals("frm_1", form.id.value)
        assertEquals(SessionId(session), event.sessionId)
        assertEquals("Setup", form.title)
        assertEquals(FormKind.Other("setup"), form.kind)
        assertEquals(mapOf("kind" to DataValue.Text("setup")), form.metadata)
        assertNull(form.state)
        assertEquals(
            listOf(
                FormField.FreeText(
                    key = "name", title = "Name", description = "Your name", required = true,
                    minLength = 1, maxLength = 20, pattern = "^[A-Za-z]+$", placeholder = "Ada", defaultValue = "Ada",
                ),
                FormField.FreeText(key = "mail", title = "Mail", format = TextFormat.Email),
                FormField.SingleChoice(
                    key = "plan", title = "Plan",
                    options = listOf(FormOption("free", "Free"), FormOption("pro", "Pro", "More")), allowCustom = true,
                ),
                FormField.Number(
                    key = "seats", title = "Seats",
                    visibleWhen = listOf(FieldCondition.Equals("plan", DataValue.Text("pro"))),
                    integerOnly = true, min = BigDecimal("1"), max = BigDecimal("10"), defaultValue = BigDecimal("2"),
                ),
                FormField.Number(
                    key = "ratio",
                    visibleWhen = listOf(FieldCondition.NotEquals("plan", DataValue.Text("free")), FieldCondition.Unknown("gt")),
                    defaultValue = BigDecimal("0.5"),
                ),
                FormField.YesNo(key = "agree", title = "Agree", defaultValue = false),
                FormField.MultipleChoice(
                    key = "extras", options = listOf(FormOption("a", "A")), minSelected = 1, maxSelected = 2, defaultValue = listOf("a"),
                ),
                FormField.FreeText(key = "hidden", hidden = true, defaultValue = "x"),
                FormField.External(key = "login", title = "Sign in", description = "Opens a page", url = "https://example.invalid/login"),
                FormField.Unknown(key = "pic", rawType = "image"),
            ),
            form.fields,
        )
    }

    @Test
    fun `a question form is of kind question`() {
        val event = decode(
            "form.created",
            """{"form":{"id":"frm_2","sessionID":"$session","title":"Questions","metadata":{"kind":"question"},"fields":[]}}""",
        )
        assertEquals(FormKind.Question, (event as ServerEvent.FormCreated).form.kind)
        val other = decode("form.created", """{"form":{"id":"frm_3","sessionID":"$session"}}""")
        assertEquals(FormKind.Other(null), (other as ServerEvent.FormCreated).form.kind)
    }

    @Test
    fun `a field with no key makes the form malformed`() {
        val result = EventDecoder.decodeResult(
            null,
            envelope("form.created", """{"form":{"id":"frm_1","sessionID":"$session","fields":[{"type":"string"}]}}"""),
            null,
        )
        assertEquals(DecodeResult.Malformed("form.created", "evt_1"), result)
    }

    @Test
    fun `an external field with no url cannot be drawn`() {
        val event = decode("form.created", """{"form":{"id":"frm_1","sessionID":"$session","fields":[{"key":"login","type":"external"}]}}""")
        assertEquals(listOf<FormField>(FormField.Unknown(key = "login", rawType = "external")), (event as ServerEvent.FormCreated).form.fields)
    }
}
