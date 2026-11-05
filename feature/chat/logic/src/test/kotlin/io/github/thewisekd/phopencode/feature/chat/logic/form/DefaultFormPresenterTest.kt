package io.github.thewisekd.phopencode.feature.chat.logic.form

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.FieldCondition
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.FormOption
import io.github.thewisekd.phopencode.core.model.FormState
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.TextFormat
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldAnswer
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldProblem
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormsState
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A [ChatSession] the test drives; [call] runs on [client] only while [connected]. */
private class FakeChatSession(override val sessionId: SessionId) : ChatSession {
    val client = FakeOpencodeClient()

    val mutableState = MutableStateFlow(ChatSessionState())

    override val state: StateFlow<ChatSessionState> = mutableState

    @Volatile
    var connected = true

    override suspend fun <T> call(block: suspend (OpencodeClient) -> T): T {
        if (!connected) throw ApiException(ApiError.Closed)
        return block(client)
    }

    fun asked(name: String): Int = client.control.calls.count { it == name }
}

/** The form logic on this session's own forms, which come from the session's sync. */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultFormPresenterTest {
    private val root = SessionId("ses_form_root")
    private val folder = "/srv/project"
    private val marker = "model-" + "text-" + "marker"

    private val single = FormField.SingleChoice(
        key = "q0",
        title = "Which branch?",
        required = true,
        options = listOf(FormOption("main", "Main"), FormOption("dev", "Dev")),
        allowCustom = true,
    )

    private val multiple = FormField.MultipleChoice(
        key = "q1",
        title = "Which checks?",
        required = true,
        options = listOf(FormOption("lint", "Lint"), FormOption("unit", "Unit"), FormOption("e2e", "E2E")),
        allowCustom = true,
    )

    private fun question(id: String = "frm_q", vararg fields: FormField = arrayOf(single, multiple)) = Form(
        id = FormId(id),
        sessionId = root,
        kind = FormKind.Question,
        title = "Questions",
        fields = fields.toList(),
    )

    private fun form(id: String, vararg fields: FormField, title: String? = "Settings") = Form(
        id = FormId(id),
        sessionId = root,
        kind = FormKind.Other("config"),
        title = title,
        fields = fields.toList(),
    )

    private fun FakeChatSession.show(vararg forms: Form, availability: ChatAvailability = ChatAvailability.Live) {
        forms.forEach { client.interactions.seedForm(it) }
        mutableState.value = ChatSessionState(
            availability = availability,
            session = SessionState(session = Session(id = root, directory = folder), forms = forms.toList()),
            phase = SyncPhase.Live,
        )
    }

    private fun TestScope.setup(): Pair<FakeChatSession, DefaultFormPresenter> {
        val chat = FakeChatSession(root)
        chat.client.sessions.seed(Session(id = root, directory = folder))
        val presenter = DefaultFormPresenter(route = ChatRoute.Chat(root.value), scope = backgroundScope, session = chat)
        return chat to presenter
    }

    private fun DefaultFormPresenter.card(id: String = "frm_q"): FormCardState =
        state.value.cards.single { it.formId == FormId(id) }

    private fun DefaultFormPresenter.answer(key: String, answer: FieldAnswer, id: String = "frm_q") =
        onIntent(FormIntent.Answer(FormId(id), key, answer))

    private fun FakeChatSession.answered(id: String = "frm_q") =
        (client.interactions.stateOf(FormId(id)) as? FormState.Answered)?.answer

    private fun DefaultFormPresenter.problemOf(key: String, id: String = "frm_q"): FieldProblem? =
        card(id).fields.single { it.field.key == key }.problem

    @Test
    fun `nothing runs in the constructor`() = runTest {
        val (chat, presenter) = setup()
        chat.show(question())

        assertEquals(FormsState(), presenter.state.value)
        assertTrue(chat.client.control.calls.isEmpty())

        runCurrent()

        val card = presenter.card()
        assertTrue(card.question)
        assertFalse(card.fromSubagent)
        assertEquals(root, card.sessionId)
        assertEquals("Questions", card.title)
        assertEquals(listOf("q0", "q1"), card.fields.map { it.field.key })
        assertEquals(FieldAnswer.SingleChoice(), card.fields[0].answer)
        assertEquals(FieldAnswer.MultipleChoice(), card.fields[1].answer)
    }

    @Test
    fun `cards follow the session's forms, oldest first`() = runTest {
        val (chat, presenter) = setup()
        val first = form("frm_a", FormField.YesNo(key = "ok"))
        val second = form("frm_b", FormField.YesNo(key = "ok"))
        chat.show(first)
        runCurrent()
        chat.show(first, second)
        runCurrent()

        assertEquals(listOf("frm_a", "frm_b"), presenter.state.value.cards.map { it.formId.value })

        chat.show(second)
        runCurrent()

        assertEquals(listOf("frm_b"), presenter.state.value.cards.map { it.formId.value })
    }

    @Test
    fun `a question's answers go under q0 and q1, the own answer last`() = runTest {
        val (chat, presenter) = setup()
        chat.show(question())
        runCurrent()

        presenter.answer("q0", FieldAnswer.SingleChoice(own = "  release  "))
        presenter.answer("q1", FieldAnswer.MultipleChoice(options = setOf("e2e", "lint"), own = "fuzz"))
        assertTrue(presenter.card().canSubmit)
        presenter.onIntent(FormIntent.Submit(FormId("frm_q")))
        runCurrent()

        assertEquals(
            mapOf(
                "q0" to DataValue.Text("release"),
                "q1" to DataValue.Items(listOf(DataValue.Text("lint"), DataValue.Text("e2e"), DataValue.Text("fuzz"))),
            ),
            chat.answered(),
        )
        assertTrue(presenter.state.value.cards.isEmpty())
    }

    @Test
    fun `a picked option goes as its value`() = runTest {
        val (chat, presenter) = setup()
        chat.show(question())
        runCurrent()

        presenter.answer("q0", FieldAnswer.SingleChoice(option = "dev"))
        presenter.answer("q1", FieldAnswer.MultipleChoice(options = setOf("unit")))
        presenter.onIntent(FormIntent.Submit(FormId("frm_q")))
        runCurrent()

        assertEquals(
            mapOf("q0" to DataValue.Text("dev"), "q1" to DataValue.Items(listOf(DataValue.Text("unit")))),
            chat.answered(),
        )
    }

    @Test
    fun `required questions with no answer block the submit and point at the first`() = runTest {
        val (chat, presenter) = setup()
        chat.show(question())
        runCurrent()
        presenter.answer("q0", FieldAnswer.SingleChoice(option = "main"))
        presenter.onIntent(FormIntent.GoToStep(FormId("frm_q"), 0))

        assertEquals(FieldProblem.Required, presenter.problemOf("q1"))
        assertFalse(presenter.card().canSubmit)

        presenter.onIntent(FormIntent.Submit(FormId("frm_q")))
        runCurrent()

        val card = presenter.card()
        assertTrue(card.attempted)
        assertEquals(1, card.step)
        assertEquals(0, chat.asked("interactions.replyForm"))
    }

    @Test
    fun `an own answer with no text is no answer`() = runTest {
        val (chat, presenter) = setup()
        chat.show(question())
        runCurrent()

        presenter.answer("q0", FieldAnswer.SingleChoice(own = "  "))

        assertEquals(FieldProblem.Required, presenter.problemOf("q0"))
    }

    @Test
    fun `answers of the wrong kind or outside the options are ignored`() = runTest {
        val (chat, presenter) = setup()
        val closed = single.copy(allowCustom = false)
        chat.show(question("frm_q", closed, multiple.copy(allowCustom = false)))
        runCurrent()

        presenter.answer("q0", FieldAnswer.Text("main"))
        presenter.answer("q0", FieldAnswer.SingleChoice(option = "nope"))
        presenter.answer("q0", FieldAnswer.SingleChoice(own = "mine"))
        presenter.answer("q1", FieldAnswer.MultipleChoice(options = setOf("lint", "nope")))
        presenter.answer("q1", FieldAnswer.MultipleChoice(own = "mine"))
        presenter.answer("missing", FieldAnswer.Text("x"))

        assertEquals(FieldAnswer.SingleChoice(), presenter.card().fields[0].answer)
        assertEquals(FieldAnswer.MultipleChoice(), presenter.card().fields[1].answer)
    }

    @Test
    fun `choices outside min and max are problems`() = runTest {
        val (chat, presenter) = setup()
        val picks = FormField.MultipleChoice(
            key = "picks",
            options = listOf(FormOption("a", "A"), FormOption("b", "B"), FormOption("c", "C")),
            minSelected = 2,
            maxSelected = 2,
        )
        chat.show(form("frm_m", picks))
        runCurrent()

        assertNull(presenter.problemOf("picks", "frm_m"), "Not required, and nothing picked")
        presenter.answer("picks", FieldAnswer.MultipleChoice(setOf("a")), "frm_m")
        assertEquals(FieldProblem.TooFewChoices, presenter.problemOf("picks", "frm_m"))
        presenter.answer("picks", FieldAnswer.MultipleChoice(setOf("a", "b", "c")), "frm_m")
        assertEquals(FieldProblem.TooManyChoices, presenter.problemOf("picks", "frm_m"))
        presenter.answer("picks", FieldAnswer.MultipleChoice(setOf("c", "a")), "frm_m")
        assertNull(presenter.problemOf("picks", "frm_m"))

        presenter.onIntent(FormIntent.Submit(FormId("frm_m")))
        runCurrent()

        assertEquals(mapOf("picks" to DataValue.Items(listOf(DataValue.Text("a"), DataValue.Text("c")))), chat.answered("frm_m"))
    }

    @Test
    fun `free text is checked for length and format, and its pattern is left to the server`() = runTest {
        val (chat, presenter) = setup()
        val name = FormField.FreeText(key = "name", required = true, minLength = 3, maxLength = 5, pattern = "[a-z]+")
        val mail = FormField.FreeText(key = "mail", format = TextFormat.Email)
        val day = FormField.FreeText(key = "day", format = TextFormat.Date)
        val link = FormField.FreeText(key = "link", format = TextFormat.Uri)
        chat.show(form("frm_t", name, mail, day, link))
        runCurrent()

        fun problem(key: String) = presenter.problemOf(key, "frm_t")
        fun type(key: String, text: String) = presenter.answer(key, FieldAnswer.Text(text), "frm_t")

        assertEquals(FieldProblem.Required, problem("name"))
        type("name", "ab")
        assertEquals(FieldProblem.TooShort, problem("name"))
        type("name", "abcdef")
        assertEquals(FieldProblem.TooLong, problem("name"))
        type("name", "ab1d")
        assertNull(problem("name"), "The server alone checks a pattern")
        type("name", "abcd")
        assertNull(problem("name"))

        assertNull(problem("mail"), "Not required, and empty")
        type("mail", "not-mail")
        assertEquals(FieldProblem.WrongFormat, problem("mail"))
        type("mail", "a@example.org")
        assertNull(problem("mail"))

        type("day", "2026-13-01")
        assertEquals(FieldProblem.WrongFormat, problem("day"))
        type("day", "2026-11-05")
        assertNull(problem("day"))

        type("link", "no scheme")
        assertEquals(FieldProblem.WrongFormat, problem("link"))
        type("link", "https://example.org/a")
        assertNull(problem("link"))

        presenter.onIntent(FormIntent.Submit(FormId("frm_t")))
        runCurrent()

        assertEquals(
            mapOf(
                "name" to DataValue.Text("abcd"),
                "mail" to DataValue.Text("a@example.org"),
                "day" to DataValue.Text("2026-11-05"),
                "link" to DataValue.Text("https://example.org/a"),
            ),
            chat.answered("frm_t"),
        )
    }

    @Test
    fun `a pattern that does not even compile does not block the form`() = runTest {
        val (chat, presenter) = setup()
        chat.show(form("frm_p", FormField.FreeText(key = "x", pattern = "(unclosed")))
        runCurrent()

        presenter.answer("x", FieldAnswer.Text("anything"), "frm_p")

        assertNull(presenter.problemOf("x", "frm_p"))
    }

    @Test
    fun `numbers are parsed, checked and sent as numbers`() = runTest {
        val (chat, presenter) = setup()
        val count = FormField.Number(key = "count", integerOnly = true, min = BigDecimal.ONE, max = BigDecimal.TEN)
        val ratio = FormField.Number(key = "ratio", required = true)
        chat.show(form("frm_n", count, ratio))
        runCurrent()

        fun problem(key: String) = presenter.problemOf(key, "frm_n")
        fun type(key: String, text: String) = presenter.answer(key, FieldAnswer.Number(text), "frm_n")

        assertNull(problem("count"))
        assertEquals(FieldProblem.Required, problem("ratio"))
        type("count", "abc")
        assertEquals(FieldProblem.NotANumber, problem("count"))
        type("count", "1e3")
        assertEquals(FieldProblem.NotANumber, problem("count"))
        type("count", "2.5")
        assertEquals(FieldProblem.NotAnInteger, problem("count"))
        type("count", "0")
        assertEquals(FieldProblem.BelowMinimum, problem("count"))
        type("count", "11")
        assertEquals(FieldProblem.AboveMaximum, problem("count"))
        type("count", " 4.0 ")
        assertNull(problem("count"))
        type("ratio", "0,25")
        assertNull(problem("ratio"))

        presenter.onIntent(FormIntent.Submit(FormId("frm_n")))
        runCurrent()

        assertEquals(
            mapOf("count" to DataValue.Num(BigDecimal(4)), "ratio" to DataValue.Num(BigDecimal("0.25"))),
            chat.answered("frm_n"),
        )
    }

    @Test
    fun `yes or no is sent as a boolean, and defaults fill the fields`() = runTest {
        val (chat, presenter) = setup()
        val confirm = FormField.YesNo(key = "confirm", required = true)
        val keep = FormField.YesNo(key = "keep", defaultValue = true)
        val note = FormField.FreeText(key = "note", defaultValue = "hello")
        val level = FormField.Number(key = "level", defaultValue = BigDecimal("2"))
        val pick = FormField.SingleChoice(key = "pick", options = listOf(FormOption("a", "A")), defaultValue = "a")
        val many = FormField.MultipleChoice(key = "many", options = listOf(FormOption("a", "A"), FormOption("b", "B")), defaultValue = listOf("b"))
        chat.show(form("frm_y", confirm, keep, note, level, pick, many))
        runCurrent()

        val answers = presenter.card("frm_y").fields.map { it.answer }
        assertEquals(
            listOf(
                FieldAnswer.YesNo(null),
                FieldAnswer.YesNo(true),
                FieldAnswer.Text("hello"),
                FieldAnswer.Number("2"),
                FieldAnswer.SingleChoice(option = "a"),
                FieldAnswer.MultipleChoice(options = setOf("b")),
            ),
            answers,
        )
        assertEquals(FieldProblem.Required, presenter.problemOf("confirm", "frm_y"))

        presenter.answer("confirm", FieldAnswer.YesNo(false), "frm_y")
        presenter.onIntent(FormIntent.Submit(FormId("frm_y")))
        runCurrent()

        assertEquals(
            mapOf(
                "confirm" to DataValue.Bool(false),
                "keep" to DataValue.Bool(true),
                "note" to DataValue.Text("hello"),
                "level" to DataValue.Num(BigDecimal(2)),
                "pick" to DataValue.Text("a"),
                "many" to DataValue.Items(listOf(DataValue.Text("b"))),
            ),
            chat.answered("frm_y"),
        )
    }

    @Test
    fun `when shows a field only while its condition holds, and hidden fields never`() = runTest {
        val (chat, presenter) = setup()
        val mode = FormField.SingleChoice(key = "mode", options = listOf(FormOption("auto", "Auto"), FormOption("manual", "Manual")))
        val path = FormField.FreeText(
            key = "path",
            required = true,
            visibleWhen = listOf(FieldCondition.Equals("mode", DataValue.Text("manual"))),
        )
        val note = FormField.FreeText(
            key = "note",
            visibleWhen = listOf(FieldCondition.NotEquals("mode", DataValue.Text("auto"))),
        )
        val secretField = FormField.FreeText(key = "token", hidden = true, defaultValue = "x")
        val odd = FormField.YesNo(key = "odd", visibleWhen = listOf(FieldCondition.Unknown("gt")))
        chat.show(form("frm_w", mode, path, note, secretField, odd))
        runCurrent()

        fun keys() = presenter.card("frm_w").fields.map { it.field.key }

        assertEquals(listOf("mode", "note", "odd"), keys())
        presenter.answer("mode", FieldAnswer.SingleChoice(option = "manual"), "frm_w")
        assertEquals(listOf("mode", "path", "note", "odd"), keys())
        presenter.answer("path", FieldAnswer.Text("/tmp/x"), "frm_w")
        presenter.answer("note", FieldAnswer.Text("kept"), "frm_w")
        presenter.answer("mode", FieldAnswer.SingleChoice(option = "auto"), "frm_w")
        assertEquals(listOf("mode", "odd"), keys())
        assertTrue(presenter.card("frm_w").canSubmit, "A required field that is not shown does not block")

        presenter.answer("mode", FieldAnswer.SingleChoice(option = "manual"), "frm_w")
        assertEquals(FieldAnswer.Text("/tmp/x"), presenter.card("frm_w").fields[1].answer, "The answer comes back with the field")
        presenter.answer("mode", FieldAnswer.SingleChoice(option = "auto"), "frm_w")
        presenter.onIntent(FormIntent.Submit(FormId("frm_w")))
        runCurrent()

        assertEquals(mapOf("mode" to DataValue.Text("auto")), chat.answered("frm_w"))
    }

    @Test
    fun `an unknown field type is never answered, and blocks only when required`() = runTest {
        val (chat, presenter) = setup()
        val odd = FormField.Unknown(key = "odd", rawType = "color")
        chat.show(form("frm_u", odd, FormField.YesNo(key = "ok", defaultValue = true)))
        runCurrent()

        presenter.answer("odd", FieldAnswer.Text("red"), "frm_u")
        assertEquals(FieldAnswer.Unsupported, presenter.card("frm_u").fields[0].answer)
        assertNull(presenter.problemOf("odd", "frm_u"))
        presenter.onIntent(FormIntent.Submit(FormId("frm_u")))
        runCurrent()

        assertEquals(mapOf("ok" to DataValue.Bool(true)), chat.answered("frm_u"))
    }

    @Test
    fun `a required unknown field can only be declined`() = runTest {
        val (chat, presenter) = setup()
        chat.show(form("frm_r", FormField.Unknown(key = "odd", required = true, rawType = "color")))
        runCurrent()

        assertEquals(FieldProblem.Unsupported, presenter.problemOf("odd", "frm_r"))
        assertFalse(presenter.card("frm_r").canSubmit)
        presenter.onIntent(FormIntent.Submit(FormId("frm_r")))
        runCurrent()
        assertEquals(0, chat.asked("interactions.replyForm"))

        presenter.onIntent(FormIntent.Decline(FormId("frm_r")))
        runCurrent()

        assertIs<FormState.Cancelled>(chat.client.interactions.stateOf(FormId("frm_r")))
        assertTrue(presenter.state.value.cards.isEmpty())
    }

    @Test
    fun `declining a question cancels it`() = runTest {
        val (chat, presenter) = setup()
        chat.show(question())
        runCurrent()

        presenter.onIntent(FormIntent.Decline(FormId("frm_q")))
        assertTrue(presenter.card().declining)
        assertFalse(presenter.card().canDecline)
        runCurrent()

        assertEquals(1, chat.asked("interactions.cancelForm"))
        assertIs<FormState.Cancelled>(chat.client.interactions.stateOf(FormId("frm_q")))
        assertTrue(presenter.state.value.cards.isEmpty())
    }

    @Test
    fun `a failed decline keeps the card and shows why`() = runTest {
        val (chat, presenter) = setup()
        chat.show(question())
        runCurrent()
        chat.client.control.failNext(ApiError.Network)

        presenter.onIntent(FormIntent.Decline(FormId("frm_q")))
        runCurrent()

        assertEquals(FormError(FormAction.Decline, ChatFailure.Unreachable), presenter.card().error)
        assertFalse(presenter.card().declining)

        presenter.onIntent(FormIntent.DismissError(FormId("frm_q")))
        assertNull(presenter.card().error)
    }

    @Test
    fun `a failed reply keeps the card and its answers`() = runTest {
        val (chat, presenter) = setup()
        chat.show(question())
        runCurrent()
        presenter.answer("q0", FieldAnswer.SingleChoice(option = "main"))
        presenter.answer("q1", FieldAnswer.MultipleChoice(options = setOf("lint")))
        chat.client.control.failNext(ApiError.BadRequest("refused"))

        presenter.onIntent(FormIntent.Submit(FormId("frm_q")))
        runCurrent()

        val card = presenter.card()
        assertEquals(FormError(FormAction.Submit, ChatFailure.Rejected), card.error)
        assertFalse(card.submitting)
        assertEquals(FieldAnswer.SingleChoice(option = "main"), card.fields[0].answer)
        assertEquals(FieldAnswer.MultipleChoice(options = setOf("lint")), card.fields[1].answer)
    }

    @Test
    fun `a settled form shows a conflict`() = runTest {
        val (chat, presenter) = setup()
        chat.show(form("frm_c", FormField.YesNo(key = "ok", defaultValue = true)))
        runCurrent()
        chat.client.control.failNext(ApiError.Conflict("settled"))

        presenter.onIntent(FormIntent.Submit(FormId("frm_c")))
        runCurrent()

        assertEquals(FormError(FormAction.Submit, ChatFailure.Conflict), presenter.card("frm_c").error)
    }

    @Test
    fun `not connected to the bound server, nothing is sent`() = runTest {
        val (chat, presenter) = setup()
        chat.show(form("frm_c", FormField.YesNo(key = "ok", defaultValue = true)))
        runCurrent()
        chat.connected = false

        presenter.onIntent(FormIntent.Submit(FormId("frm_c")))
        runCurrent()

        assertEquals(FormError(FormAction.Submit, ChatFailure.NotConnected), presenter.card("frm_c").error)
        assertEquals(0, chat.asked("interactions.replyForm"))
    }

    @Test
    fun `one tap sends once, and answers cannot change while it is on its way`() = runTest {
        val (chat, presenter) = setup()
        chat.show(form("frm_c", FormField.YesNo(key = "ok", defaultValue = true)))
        runCurrent()
        chat.client.control.stall()

        presenter.onIntent(FormIntent.Submit(FormId("frm_c")))
        presenter.onIntent(FormIntent.Submit(FormId("frm_c")))
        presenter.onIntent(FormIntent.Decline(FormId("frm_c")))
        presenter.answer("ok", FieldAnswer.YesNo(false), "frm_c")
        runCurrent()

        assertTrue(presenter.card("frm_c").submitting)
        assertFalse(presenter.card("frm_c").canSubmit)
        assertEquals(FieldAnswer.YesNo(true), presenter.card("frm_c").fields[0].answer)
        assertEquals(1, chat.asked("interactions.replyForm"))
        assertEquals(0, chat.asked("interactions.cancelForm"))

        chat.client.control.resume()
        runCurrent()

        assertEquals(mapOf("ok" to DataValue.Bool(true)), chat.answered("frm_c"))
    }

    @Test
    fun `steps move and are clamped`() = runTest {
        val (chat, presenter) = setup()
        chat.show(question())
        runCurrent()

        presenter.onIntent(FormIntent.GoToStep(FormId("frm_q"), 1))
        assertEquals(1, presenter.card().step)
        assertTrue(presenter.card().isLastStep)
        assertEquals(multiple, presenter.card().currentField?.field)
        presenter.onIntent(FormIntent.GoToStep(FormId("frm_q"), 9))
        assertEquals(1, presenter.card().step)
        presenter.onIntent(FormIntent.GoToStep(FormId("frm_q"), -3))
        assertEquals(0, presenter.card().step)
    }

    @Test
    fun `an external step opens only web addresses and is answered with true`() = runTest {
        val (chat, presenter) = setup()
        val login = FormField.External(key = "login", url = "https://auth.example/start?code=" + marker)
        val bad = FormField.External(key = "bad", url = "javascript:alert(1)")
        chat.show(form("frm_e", login), form("frm_x", bad))
        runCurrent()
        val events = mutableListOf<FormEvent>()
        backgroundScope.launch { presenter.events.toList(events) }
        runCurrent()

        assertEquals(FieldProblem.NotOpened, presenter.problemOf("login", "frm_e"))
        presenter.answer("login", FieldAnswer.External(opened = true), "frm_e")
        assertEquals(FieldProblem.NotOpened, presenter.problemOf("login", "frm_e"), "Only opening it counts")

        presenter.onIntent(FormIntent.OpenExternal(FormId("frm_x"), "bad"))
        runCurrent()
        assertTrue(events.isEmpty())
        assertEquals(FieldProblem.NotOpened, presenter.problemOf("bad", "frm_x"))

        presenter.onIntent(FormIntent.OpenExternal(FormId("frm_e"), "login"))
        runCurrent()
        assertEquals(listOf<FormEvent>(FormEvent.OpenUrl(login.url)), events)
        assertNull(presenter.problemOf("login", "frm_e"))

        presenter.onIntent(FormIntent.Submit(FormId("frm_e")))
        runCurrent()

        assertEquals(mapOf("login" to DataValue.Bool(true)), chat.answered("frm_e"))
    }

    @Test
    fun `other schemes are not opened`() = runTest {
        val (chat, presenter) = setup()
        val urls = listOf("file:///etc/passwd", "intent://x#Intent;end", "http:no-host", "ftp://example.org", " https://x.example")
        chat.show(form("frm_x", *urls.mapIndexed { i, url -> FormField.External(key = "e$i", url = url) }.toTypedArray()))
        runCurrent()
        val events = mutableListOf<FormEvent>()
        backgroundScope.launch { presenter.events.toList(events) }

        urls.indices.forEach { presenter.onIntent(FormIntent.OpenExternal(FormId("frm_x"), "e$it")) }
        runCurrent()

        assertTrue(events.isEmpty())
    }

    @Test
    fun `nothing prints the user's answers or the model's text`() = runTest {
        val (chat, presenter) = setup()
        val field = FormField.FreeText(key = "pw", title = "Title " + marker)
        chat.show(form("frm_s", field, title = "Form " + marker))
        runCurrent()

        presenter.answer("pw", FieldAnswer.Text(marker), "frm_s")
        presenter.onIntent(FormIntent.OpenExternal(FormId("frm_s"), "pw"))

        val printed = presenter.state.value.toString()
        assertFalse(marker in printed, printed)
        assertFalse(marker in FormEvent.OpenUrl("https://x.example/$marker").toString())
    }
}
