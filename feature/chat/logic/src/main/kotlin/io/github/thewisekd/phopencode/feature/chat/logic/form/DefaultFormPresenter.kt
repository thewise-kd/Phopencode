package io.github.thewisekd.phopencode.feature.chat.logic.form

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormAnswers
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldAnswer
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormFieldState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * The forms of this session and its subagents, as cards (P7.2), by the rules in `FormContract.kt`.
 *
 * - This session's forms are `SessionState.forms` of [session], which its sync keeps.
 * - Its subagents' forms, at any depth: once the chat is live with the session loaded, one event
 *   stream (`form.created`, `form.replied`, `form.cancelled`) runs inside [ChatSession.call]. Each
 *   `Connected` on it lists the forms of the session's folder again
 *   (`listForms(sessionId = null, Location(directory))`), keeping those whose session descends from
 *   this one. Descent is found by walking `Session.parentId` up with `SessionApi.get`, cached, at
 *   most [MAX_DEPTH] steps and never through the same session twice. A `form.created` event is
 *   admitted by the same rule as a list: its session must descend from this one and work in the
 *   same folder, so no card comes from an event only to vanish on the next list. Known limit: a
 *   session with no folder lists the server's default folder, whose path is not known here, so
 *   its events are then admitted from a descendant in any folder.
 * - The stream is started anew each time the chat becomes live, and on [FormIntent.Retry] when none
 *   runs: the one before is cancelled and joined first, so two never run at once, even when the old
 *   one ends only after the chat is live again. Nothing here retries by itself.
 *   Known limit: two reconnects in quick succession can let two streams overlap briefly.
 * - Every subagent result, from a list (on `Connected` or [FormIntent.Retry]) or an event, belongs
 *   to the generation it started in. Losing the session ([ChatAvailability.OtherServer] or
 *   [ChatAvailability.NotFound]) starts a new generation and cancels the work of the old one, so a
 *   slow list that ends afterwards brings no card back.
 * - Fields, checks and the reply's encoding are in [FormFields].
 * - A reply or a decline the server accepted hides its card at once; the card is gone for good once
 *   the server's events or lists no longer have the form. A failed one keeps the card and its
 *   answers, and shows the error.
 *
 * Work starts in [scope] after a `yield()`, never in the constructor. Every server call goes through
 * [ChatSession.call]. Nothing here logs; answers, titles and URLs are user or model text, and the
 * contract's types print them masked.
 *
 * @param route The destination's route; the session is [session]'s.
 * @param scope The destination's scope; work started here stops with it.
 * @param session The destination's session, shared with its other presenters.
 */
@Suppress("unused")
class DefaultFormPresenter(
    private val route: ChatRoute,
    private val scope: CoroutineScope,
    private val session: ChatSession,
) : FormPresenter {
    /** What the user did on one card. */
    private data class CardUi(
        val answers: Map<String, FieldAnswer>,
        val step: Int = 0,
        val attempted: Boolean = false,
        val submitting: Boolean = false,
        val declining: Boolean = false,
        val error: FormError? = null,
    )

    /**
     * @property own This session's pending forms, from its sync.
     * @property sub The subagents' pending forms, in the order they came.
     * @property order When each form was first seen, for oldest first.
     * @property settled Forms the server accepted a reply or a decline for, hidden until they leave.
     * @property generation Bumped each time the session goes away; subagent results of an older
     *   generation are dropped.
     */
    private data class Model(
        val own: List<Form> = emptyList(),
        val sub: Map<FormId, Form> = emptyMap(),
        val order: Map<FormId, Long> = emptyMap(),
        val nextOrder: Long = 0,
        val ui: Map<FormId, CardUi> = emptyMap(),
        val settled: Set<FormId> = emptySet(),
        val error: ChatFailure? = null,
        val generation: Int = 0,
    ) {
        fun formOf(id: FormId): Form? = own.firstOrNull { it.id == id } ?: sub[id]
    }

    private val mutableState = MutableStateFlow(FormsState())

    override val state: StateFlow<FormsState> = mutableState.asStateFlow()

    private val eventChannel = Channel<FormEvent>(Channel.UNLIMITED)

    override val events: Flow<FormEvent> = eventChannel.receiveAsFlow()

    /** Guards [model], and publishes each change of it to [mutableState] in order. */
    private val lock = Any()

    private var model = Model()

    /** Serializes the subagent lists and events, and guards [sessions]. */
    private val serverMutex = Mutex()

    /** What `SessionApi.get` reported of a session: its parent (null for a root) and its folder. */
    private data class SessionInfo(val parentId: SessionId?, val directory: String?)

    /** Each session looked up so far. */
    private val sessions = HashMap<SessionId, SessionInfo>()

    /** Touched only in [scope]. */
    private var streamJob: Job? = null

    /** A list [FormIntent.Retry] started; touched only in [scope]. */
    private var relistJob: Job? = null

    /** Touched only in [scope]: whether the last chat state seen was live with the session loaded. */
    private var wasLive = false

    init {
        scope.launch {
            yield()
            session.state.collect(::follow)
        }
    }

    override fun onIntent(intent: FormIntent) {
        when (intent) {
            is FormIntent.Answer -> answer(intent)
            is FormIntent.GoToStep -> changeCard(intent.formId) { form, ui ->
                val count = cardOf(form, ui).fields.size
                ui.copy(step = intent.step.coerceIn(0, maxOf(0, count - 1)))
            }
            is FormIntent.OpenExternal -> openExternal(intent)
            is FormIntent.Submit -> submit(intent.formId)
            is FormIntent.Decline -> decline(intent.formId)
            is FormIntent.DismissError -> changeCard(intent.formId) { _, ui -> ui.copy(error = null) }
            FormIntent.Retry -> scope.launch { retry() }
        }
    }

    // Following the chat

    private fun follow(chat: ChatSessionState) {
        val loaded = chat.session
        val gone = chat.availability == ChatAvailability.OtherServer || chat.availability == ChatAvailability.NotFound
        if (gone) {
            // Kept, so that the next start joins it before opening another.
            streamJob?.cancel()
            relistJob?.cancel()
        }
        change {
            it.copy(
                own = loaded?.forms.orEmpty(),
                sub = if (gone) emptyMap() else it.sub,
                error = if (gone) null else it.error,
                generation = if (gone) it.generation + 1 else it.generation,
            )
        }
        val live = chat.availability == ChatAvailability.Live && loaded != null
        if (live && !wasLive) startStream()
        wasLive = live
    }

    private fun retry() {
        change { it.copy(error = null) }
        if (streamJob?.isActive == true) {
            relistJob?.cancel()
            val generation = currentGeneration()
            relistJob = scope.launch { serverMutex.withLock { relist(generation) } }
        } else if (wasLive) {
            startStream()
        }
    }

    /** Follows the subagents' forms on the bound server until its connection ends. */
    private fun startStream() {
        val previous = streamJob
        streamJob = scope.launch {
            // One stream at a time: the old one may still be ending on a connection that is gone.
            previous?.cancelAndJoin()
            try {
                session.call { client ->
                    client.events.events(FORM_EVENTS).collect { event -> serverMutex.withLock { handle(event) } }
                }
            } catch (e: ApiException) {
                // A closed connection is the chat's to show; the stream starts again once it is live.
                if (e.error != ApiError.Closed) change { it.copy(error = ChatFailure.of(e.error)) }
            }
        }
    }

    private suspend fun handle(event: ServerEvent) {
        val generation = currentGeneration()
        when (event) {
            is ServerEvent.Connected -> relist(generation)
            is ServerEvent.FormCreated -> {
                val form = event.form
                if (form.sessionId == session.sessionId) return
                try {
                    if (inFolder(form.sessionId) && descends(form.sessionId)) {
                        changeIn(generation) { it.copy(sub = it.sub + (form.id to form)) }
                    }
                } catch (e: ApiException) {
                    changeIn(generation) { it.copy(error = ChatFailure.of(e.error)) }
                }
            }
            is ServerEvent.FormReplied -> change { it.copy(sub = it.sub - event.formId) }
            is ServerEvent.FormCancelled -> change { it.copy(sub = it.sub - event.formId) }
            else -> Unit
        }
    }

    /** Lists the subagents' forms again. Called holding [serverMutex]. */
    private suspend fun relist(generation: Int) {
        val directory = session.state.value.session?.session?.directory
        try {
            val listed = session.call { it.interactions.listForms(sessionId = null, location = directory?.let(::Location)) }
            val mine = LinkedHashMap<FormId, Form>()
            listed.forEach { form -> if (form.sessionId != session.sessionId && descends(form.sessionId)) mine[form.id] = form }
            changeIn(generation) { it.copy(sub = mine, error = null) }
        } catch (e: ApiException) {
            changeIn(generation) { it.copy(error = ChatFailure.of(e.error)) }
        }
    }

    /**
     * Whether [id] works in the folder a list asks for, so an event admits only what a list would.
     * Called holding [serverMutex].
     */
    private suspend fun inFolder(id: SessionId): Boolean {
        val directory = session.state.value.session?.session?.directory
        val info = infoOf(id) ?: return false
        // With no folder of its own the chat lists the server's default folder, which is not known here.
        return directory == null || info.directory == directory
    }

    /** Whether [id] is a subagent of this session, at any depth. Called holding [serverMutex]. */
    private suspend fun descends(id: SessionId): Boolean {
        val seen = HashSet<SessionId>()
        var current: SessionId? = id
        repeat(MAX_DEPTH) {
            val at = current ?: return false
            if (at == session.sessionId) return at != id
            if (!seen.add(at)) return false
            current = infoOf(at)?.parentId
        }
        // The node reached on the last step is checked too, so a subagent exactly MAX_DEPTH deep is found.
        return current == session.sessionId
    }

    /** The session [id], looked up once; null when the server no longer has it. */
    private suspend fun infoOf(id: SessionId): SessionInfo? {
        sessions[id]?.let { return it }
        return try {
            val found = session.call { it.sessions.get(id) }
            SessionInfo(found.parentId, found.directory).also { sessions[id] = it }
        } catch (e: ApiException) {
            // A session gone meanwhile has no forms to show.
            if (e.error == ApiError.NotFound) null else throw e
        }
    }

    // Intents on a card

    private fun answer(intent: FormIntent.Answer) = changeCard(intent.formId) { form, ui ->
        val field = form.fields.firstOrNull { it.key == intent.key }
        if (field == null || ui.submitting || ui.declining || !FormFields.accepts(field, intent.answer)) {
            ui
        } else {
            ui.copy(answers = ui.answers + (intent.key to intent.answer))
        }
    }

    private fun openExternal(intent: FormIntent.OpenExternal) {
        var url: String? = null
        changeCard(intent.formId) { form, ui ->
            val field = form.fields.firstOrNull { it.key == intent.key } as? FormField.External
            if (field == null || !FormFields.isWebUrl(field.url)) {
                ui
            } else {
                url = field.url
                ui.copy(answers = ui.answers + (field.key to FieldAnswer.External(opened = true)))
            }
        }
        // Unlimited, so this never fails while the presenter lives.
        url?.let { eventChannel.trySend(FormEvent.OpenUrl(it)) }
    }

    private fun submit(id: FormId) {
        var claimed: Pair<Form, FormAnswers>? = null
        changeCard(id) { form, ui ->
            val card = cardOf(form, ui)
            when {
                card.busy -> ui
                !card.canSubmit -> ui.copy(
                    attempted = true,
                    step = card.fields.indexOfFirst { it.problem != null }.coerceAtLeast(0),
                )
                else -> {
                    claimed = form to FormFields.encodeAll(card.fields.map { it.field }, ui.answers)
                    ui.copy(submitting = true, error = null)
                }
            }
        }
        val (form, answers) = claimed ?: return
        scope.launch {
            val failure = attempt { it.interactions.replyForm(form.sessionId, form.id, answers) }
            finish(form.id, FormAction.Submit, failure)
        }
    }

    private fun decline(id: FormId) {
        var claimed: Form? = null
        changeCard(id) { form, ui ->
            if (ui.submitting || ui.declining) {
                ui
            } else {
                claimed = form
                ui.copy(declining = true, error = null)
            }
        }
        val form = claimed ?: return
        scope.launch {
            val failure = attempt { it.interactions.cancelForm(form.sessionId, form.id) }
            finish(form.id, FormAction.Decline, failure)
        }
    }

    private fun finish(id: FormId, action: FormAction, failure: ChatFailure?) = change { current ->
        val ui = current.ui[id] ?: return@change current
        val done = ui.copy(submitting = false, declining = false)
        if (failure == null) {
            current.copy(ui = current.ui + (id to done), settled = current.settled + id)
        } else {
            current.copy(ui = current.ui + (id to done.copy(error = FormError(action, failure))))
        }
    }

    /** Runs [block] on the bound server; null when it succeeded, else why it failed. */
    private suspend fun attempt(block: suspend (OpencodeClient) -> Unit): ChatFailure? =
        try {
            session.call(block)
            null
        } catch (e: ApiException) {
            ChatFailure.of(e.error)
        }

    // The model

    private fun changeCard(id: FormId, transform: (Form, CardUi) -> CardUi) = change { current ->
        val form = current.formOf(id)
        val ui = current.ui[id]
        if (form == null || ui == null || id in current.settled) current else current.copy(ui = current.ui + (id to transform(form, ui)))
    }

    private fun currentGeneration(): Int = synchronized(lock) { model.generation }

    /** Applies [transform] only while the session is still the one of [generation]. */
    private fun changeIn(generation: Int, transform: (Model) -> Model) = change { current ->
        if (current.generation == generation) transform(current) else current
    }

    private fun change(transform: (Model) -> Model) {
        synchronized(lock) {
            val next = normalize(transform(model))
            model = next
            mutableState.value = render(next)
        }
    }

    /** Gives new forms their place and answers, and forgets forms that are gone. */
    private fun normalize(current: Model): Model {
        val forms = LinkedHashMap<FormId, Form>()
        current.own.forEach { forms.putIfAbsent(it.id, it) }
        current.sub.values.forEach { forms.putIfAbsent(it.id, it) }
        var next = current.nextOrder
        val order = current.order.filterKeys { it in forms }.toMutableMap()
        val ui = current.ui.filterKeys { it in forms }.toMutableMap()
        forms.values.forEach { form ->
            if (form.id !in order) order[form.id] = next++
            if (form.id !in ui) ui[form.id] = CardUi(answers = form.fields.associate { it.key to FormFields.initialAnswer(it) })
        }
        return current.copy(order = order, nextOrder = next, ui = ui, settled = current.settled.filterTo(HashSet()) { it in forms })
    }

    private fun render(current: Model): FormsState {
        val forms = LinkedHashMap<FormId, Form>()
        current.own.forEach { forms.putIfAbsent(it.id, it) }
        current.sub.values.forEach { forms.putIfAbsent(it.id, it) }
        val cards = forms.values
            .filter { it.id !in current.settled }
            .sortedBy { current.order[it.id] ?: Long.MAX_VALUE }
            .mapNotNull { form -> current.ui[form.id]?.let { cardOf(form, it) } }
        return FormsState(cards = cards, error = current.error)
    }

    private fun cardOf(form: Form, ui: CardUi): FormCardState {
        val fields = FormFields.shown(form.fields, ui.answers).map { field ->
            val answer = ui.answers[field.key] ?: FormFields.initialAnswer(field)
            FormFieldState(field = field, answer = answer, problem = FormFields.problem(field, answer))
        }
        return FormCardState(
            formId = form.id,
            sessionId = form.sessionId,
            fromSubagent = form.sessionId != session.sessionId,
            question = form.kind == FormKind.Question,
            title = form.title,
            fields = fields,
            step = ui.step.coerceIn(0, maxOf(0, fields.size - 1)),
            attempted = ui.attempted,
            submitting = ui.submitting,
            declining = ui.declining,
            error = ui.error,
        )
    }

    private companion object {
        val FORM_EVENTS = setOf("form.created", "form.replied", "form.cancelled")

        /** How far up the parent chain a subagent is looked for. */
        const val MAX_DEPTH = 64
    }
}
