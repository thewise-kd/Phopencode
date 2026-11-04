package io.github.thewisekd.phopencode.core.session.sync

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.EventMeta
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.MessageApi
import io.github.thewisekd.phopencode.core.api.MessageQuery
import io.github.thewisekd.phopencode.core.api.Page
import io.github.thewisekd.phopencode.core.api.PromptDelivery
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.SessionApi
import io.github.thewisekd.phopencode.core.model.AssistantBlock
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

internal val SESSION = SessionId("ses_sync")
internal val OTHER_SESSION = SessionId("ses_other")

internal fun at(n: Int): Instant = Instant.fromEpochSeconds(1_700_000_000L + n)

internal fun user(n: Int, text: String = "question $n"): Message.User =
    Message.User(MessageId("msg_$n"), SESSION, at(n), text)

internal fun assistant(n: Int, text: String): Message.Assistant = Message.Assistant(
    id = MessageId("msg_$n"),
    sessionId = SESSION,
    createdAt = at(n),
    completedAt = at(n),
    // Block times are left out: a seeded reducer gives a fetched block back without them.
    blocks = listOf(AssistantBlock.Text(text)),
)

internal fun form(n: Int, session: SessionId = SESSION): Form =
    Form(FormId("form_$n"), session, FormKind.Question, "form $n")

internal fun permission(n: Int, session: SessionId = SESSION): PermissionRequest =
    PermissionRequest(PermissionRequestId("perm_$n"), session, "edit", listOf("file_$n"))

internal fun meta(id: String, n: Int): EventMeta = EventMeta(id = id, createdAt = at(n))

/** Text of [message]'s first block, for checking how deltas merged. */
internal fun Message?.firstText(): String? =
    ((this as? Message.Assistant)?.blocks?.firstOrNull() as? AssistantBlock.Text)?.text

/**
 * A message API whose list call number [failOn], counted from the moment it is armed, fails once.
 * [afterInbox] and [afterList] run once, after the next such call answers: a server change that
 * lands between two calls of a fetch.
 */
internal class FlakyMessages(private val real: MessageApi) : MessageApi by real {
    @Volatile var failOn: Int? = null

    @Volatile var afterInbox: (() -> Unit)? = null

    @Volatile var afterList: (() -> Unit)? = null

    /**
     * Deliveries the server changed, which the fake server cannot store itself: [inbox] answers with
     * them. Pair it with the matching [ServerEvent.InboxDeliveryChanged].
     */
    val changedDeliveries: MutableMap<MessageId, PromptDelivery> = java.util.concurrent.ConcurrentHashMap()

    @Volatile var error: ApiError = ApiError.Network

    @Volatile private var calls = 0

    fun arm(failOn: Int, error: ApiError = ApiError.Network) {
        calls = 0
        this.error = error
        this.failOn = failOn
    }

    override suspend fun list(sessionId: SessionId, query: MessageQuery): Page<Message> {
        calls++
        if (calls == failOn) {
            failOn = null
            throw ApiException(error)
        }
        return real.list(sessionId, query).also {
            afterList?.let { hook ->
                afterList = null
                hook()
            }
        }
    }

    override suspend fun inbox(sessionId: SessionId): List<InboxItem> = real.inbox(sessionId).map { item ->
        changedDeliveries[item.id]?.let { item.copy(delivery = it) } ?: item
    }.also {
        afterInbox?.let { hook ->
            afterInbox = null
            hook()
        }
    }
}

/** A session API whose [activeStatuses] waits for [gate] while one is set. */
internal class GatedSessions(private val real: SessionApi) : SessionApi by real {
    @Volatile var gate: CompletableDeferred<Unit>? = null

    /** Completed once [activeStatuses] waits at the gate. */
    val waiting = CompletableDeferred<Unit>()

    override suspend fun activeStatuses(): Map<SessionId, SessionActivity> {
        gate?.let {
            waiting.complete(Unit)
            it.await()
        }
        return real.activeStatuses()
    }
}

/**
 * A fake server with one session, and a [SessionSync] on it.
 *
 * @param events Replaces the fake server's event stream when set.
 */
internal class SyncFixture(
    pageSize: Int = 2,
    maxBuffered: Int = SessionSync.DEFAULT_MAX_BUFFERED,
    events: Flow<ServerEvent>? = null,
) {
    val client = FakeOpencodeClient()
    val messages = FlakyMessages(client.messages)
    val sessions = GatedSessions(client.sessions)
    val sync = SessionSync(
        sessionId = SESSION,
        sessions = sessions,
        messages = messages,
        interactions = client.interactions,
        events = events ?: client.events.events(),
        retryDelays = RetryDelays(initial = 1.seconds, max = 4.seconds),
        pageSize = pageSize,
        maxBuffered = maxBuffered,
    )

    init {
        client.sessions.seed(Session(id = SESSION, title = "first title", createdAt = at(0)))
        client.sessions.seed(Session(id = OTHER_SESSION, createdAt = at(0)))
    }

    val session: SessionState
        get() = checkNotNull(sync.state.value.session) { "Not synced yet" }

    val phase: SyncPhase
        get() = sync.state.value.phase

    fun seedMessages(vararg list: Message) = client.messages.seed(SESSION, list.toList())

    fun emit(event: ServerEvent) = client.events.emit(event)

    fun start(scope: CoroutineScope): Job = scope.launch { sync.run() }

    /** The calls made since [from], by name. */
    fun callsSince(from: Int): List<String> = client.control.calls.drop(from)
}
