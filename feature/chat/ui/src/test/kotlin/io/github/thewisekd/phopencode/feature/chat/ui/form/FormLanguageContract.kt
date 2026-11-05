package io.github.thewisekd.phopencode.feature.chat.ui.form

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldAnswer
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldProblem
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormError
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the form card in one language. */
internal data class ExpectedFormStrings(
    val questionHeading: String,
    val formHeading: String,
    val fromSubagent: String,
    val step: (Int, Int) -> String,
    val required: String,
    val ownAnswer: String,
    val ownAnswerField: String,
    val answerField: String,
    val yes: String,
    val no: String,
    /** The hint of [multiField], which takes 1 to 2. */
    val choicesOneToTwo: String,
    /** The hint of [numberField]: whole, 1 to 10. */
    val numberWholeOneToTen: String,
    val externalOpen: String,
    val externalHost: (String) -> String,
    val externalOpened: String,
    val externalBlocked: String,
    val unsupported: String,
    val problems: Map<FieldProblem, String>,
    val back: String,
    val next: String,
    val submit: String,
    val decline: String,
    val sending: String,
    val declining: String,
    val declineQuestionHint: String,
    val submitFailed: (String) -> String,
    val declineFailed: (String) -> String,
    val reasons: Map<ChatFailure, String>,
    val listError: (String) -> String,
    val retry: String,
)

/** What the card says, the same in each language; a subclass sets the language and its texts. */
abstract class FormLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedFormStrings

    private var shown: FormHarness? = null

    private fun show(card: FormCardState) {
        val current = shown
        if (current == null) shown = compose.setForm(card) else current.state.value = card
        compose.waitForIdle()
    }

    /** Scrolled into view first: at API 35+ the insets push the content down. */
    private fun shows(text: String): SemanticsNodeInteraction =
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    private fun showsIn(tag: String, text: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertTextContains(text, substring = true)
    }

    @Test
    fun `a question without a title says the model asks, and counts its steps`() {
        show(question(single(), multi(), yesNo(), step = 1))

        shows(strings.questionHeading)
        shows(strings.step(2, 3))
        shows(strings.back)
        shows(strings.next)
        shows(strings.decline)
        shows(strings.declineQuestionHint)
    }

    @Test
    fun `a form without a title says input is needed, and a subagent's says so`() {
        show(form(yesNo(), title = null).copy(fromSubagent = true))

        shows(strings.formHeading)
        shows(strings.fromSubagent)
        shows(strings.submit)
        shows(strings.decline)
        compose.onNodeWithText(strings.declineQuestionHint).assertDoesNotExist()
    }

    @Test
    fun `a single choice offers your own answer`() {
        show(question(single(FieldAnswer.SingleChoice(own = ""))))

        shows(strings.required)
        showsIn(FormTags.own("q0"), strings.ownAnswer)
        showsIn(FormTags.ownInput("q0"), strings.ownAnswerField)
    }

    @Test
    fun `a multiple choice offers your own answer and says how many to pick`() {
        show(question(multi()))

        showsIn(FormTags.own("q1"), strings.ownAnswer)
        shows(strings.choicesOneToTwo)
    }

    @Test
    fun `yes and no, a number's limits and a text's label`() {
        show(form(yesNo(), number(), text()))

        showsIn(FormTags.yes("save"), strings.yes)
        showsIn(FormTags.no("save"), strings.no)
        shows(strings.numberWholeOneToTen)
        showsIn(FormTags.input("name"), strings.answerField)
    }

    @Test
    fun `an external step opens its page, says when it is open, and refuses other links`() {
        show(form(external()))
        showsIn(FormTags.open("login"), strings.externalOpen)
        shows(strings.externalHost(EXTERNAL_HOST))

        show(form(external(opened = true)))
        shows(strings.externalOpened)

        show(form(external(url = "file:///etc/passwd")))
        shows(strings.externalBlocked)
    }

    @Test
    fun `a field this app cannot draw says so`() {
        show(form(unknown()))

        shows(strings.unsupported)
        shows(strings.problems.getValue(FieldProblem.Unsupported))
    }

    @Test
    fun `every problem says what to do`() {
        val cases = listOf(
            FieldProblem.Required to text(problem = FieldProblem.Required),
            FieldProblem.TooFewChoices to multi(problem = FieldProblem.TooFewChoices),
            FieldProblem.TooManyChoices to multi(problem = FieldProblem.TooManyChoices),
            FieldProblem.NotANumber to number("x", problem = FieldProblem.NotANumber),
            FieldProblem.NotAnInteger to number("1.5", problem = FieldProblem.NotAnInteger),
            FieldProblem.BelowMinimum to number("0", problem = FieldProblem.BelowMinimum),
            FieldProblem.AboveMaximum to number("11", problem = FieldProblem.AboveMaximum),
            FieldProblem.TooShort to FormStrings.shortText,
            FieldProblem.TooLong to FormStrings.longText,
            FieldProblem.WrongFormat to text("a", problem = FieldProblem.WrongFormat),
            FieldProblem.NotOpened to external(problem = FieldProblem.NotOpened),
            FieldProblem.Unsupported to unknown(),
        )
        assertEquals(FieldProblem.entries.toSet(), cases.map { it.first }.toSet())
        cases.forEach { (problem, field) ->
            show(form(field).copy(attempted = true))
            showsIn(FormTags.problem(field.field.key), strings.problems.getValue(problem))
        }
    }

    @Test
    fun `sending and declining say so`() {
        show(form(yesNo(true)).copy(submitting = true))
        shows(strings.sending)

        show(form(yesNo(true)).copy(declining = true))
        shows(strings.declining)
    }

    @Test
    fun `a failed reply or decline says why`() {
        ChatFailure.entries.forEach { failure ->
            val reason = strings.reasons.getValue(failure)
            show(form(yesNo(true)).copy(error = FormError(FormAction.Submit, failure)))
            showsIn(FormTags.ERROR, strings.submitFailed(reason))

            show(form(yesNo(true)).copy(error = FormError(FormAction.Decline, failure)))
            showsIn(FormTags.ERROR, strings.declineFailed(reason))
        }
    }

    @Test
    fun `the list error says why and offers to retry`() {
        compose.setListError(ChatFailure.ServerFailed)

        showsIn(FormTags.LIST_ERROR, strings.listError(strings.reasons.getValue(ChatFailure.ServerFailed)))
        shows(strings.retry)
    }
}

/** Text fields with length limits, for the length problems. */
private object FormStrings {
    val shortText = text("a", problem = FieldProblem.TooShort).copy(field = textField.copy(minLength = 3))
    val longText = text("abcdef", problem = FieldProblem.TooLong).copy(field = textField.copy(maxLength = 5))
}
