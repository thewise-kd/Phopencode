package io.github.thewisekd.phopencode.feature.chat.ui.form

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldAnswer
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldProblem
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormIntent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the card draws from its state, and which intents it sends; the texts are in `FormLanguageContract`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class FormBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    /** Scrolled into view first: the card sits in a scrolling column, and the insets push it down. */
    private fun part(tag: String): SemanticsNodeInteraction =
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()

    private fun click(tag: String) {
        part(tag).performClick()
        compose.waitForIdle()
    }

    private fun absent(tag: String) {
        compose.onNodeWithTag(tag).assertDoesNotExist()
    }

    private fun answer(key: String, answer: FieldAnswer) = FormIntent.Answer(FORM_ID, key, answer)

    // Single choice

    @Test
    fun `a single choice is a group of radio buttons, and picking one sends it`() {
        val harness = compose.setForm(question(single(FieldAnswer.SingleChoice(option = SQLITE.value))))

        part(FormTags.option("q0", POSTGRES.value)).assert(hasRole(Role.RadioButton)).assertIsNotSelected()
        part(FormTags.option("q0", SQLITE.value)).assert(hasRole(Role.RadioButton)).assertIsSelected()
        part(FormTags.own("q0")).assert(hasRole(Role.RadioButton)).assertIsNotSelected()
        absent(FormTags.ownInput("q0"))

        click(FormTags.option("q0", POSTGRES.value))

        assertEquals(listOf<FormIntent>(answer("q0", FieldAnswer.SingleChoice(option = POSTGRES.value))), harness.intents)
    }

    @Test
    fun `an option shows its label and description, and the field its title and description`() {
        compose.setForm(question(single()))

        part(FormTags.option("q0", POSTGRES.value))
            .assertTextContains(POSTGRES.label)
            .assertTextContains(POSTGRES.description!!)
        compose.onNodeWithText(SINGLE_TITLE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SINGLE_DESCRIPTION).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `picking your own answer sends an empty one, and typing it sends the text`() {
        val harness = compose.setForm(question(single()))

        click(FormTags.own("q0"))
        assertEquals(listOf<FormIntent>(answer("q0", FieldAnswer.SingleChoice(own = ""))), harness.intents)

        harness.intents.clear()
        harness.state.value = question(single(FieldAnswer.SingleChoice(own = "")))
        compose.waitForIdle()
        part(FormTags.own("q0")).assertIsSelected()
        part(FormTags.ownInput("q0")).performTextReplacement(OWN_TEXT)

        assertEquals(listOf<FormIntent>(answer("q0", FieldAnswer.SingleChoice(own = OWN_TEXT))), harness.intents)
    }

    @Test
    fun `your own answer shows its text, and goes when an option is picked`() {
        val harness = compose.setForm(question(single(FieldAnswer.SingleChoice(own = OWN_TEXT))))

        part(FormTags.ownInput("q0")).assertTextContains(OWN_TEXT)

        harness.state.value = question(single(FieldAnswer.SingleChoice(option = POSTGRES.value)))
        compose.waitForIdle()
        absent(FormTags.ownInput("q0"))
    }

    @Test
    fun `a single choice without its own answer offers none`() {
        compose.setForm(
            question(FormStateFields.singleWithoutOwn),
        )

        part(FormTags.option("q0", POSTGRES.value))
        absent(FormTags.own("q0"))
    }

    // Multiple choice

    @Test
    fun `a multiple choice is checkboxes, and toggling one sends the new set`() {
        val harness = compose.setForm(question(multi(FieldAnswer.MultipleChoice(options = setOf(UNIT.value)))))

        part(FormTags.option("q1", UNIT.value)).assert(hasRole(Role.Checkbox)).assertIsOn()
        part(FormTags.option("q1", UI.value)).assert(hasRole(Role.Checkbox)).assertIsOff()
        part(FormTags.own("q1")).assert(hasRole(Role.Checkbox)).assertIsOff()

        click(FormTags.option("q1", UI.value))
        click(FormTags.option("q1", UNIT.value))

        assertEquals(
            listOf<FormIntent>(
                answer("q1", FieldAnswer.MultipleChoice(options = setOf(UNIT.value, UI.value))),
                answer("q1", FieldAnswer.MultipleChoice(options = emptySet())),
            ),
            harness.intents,
        )
    }

    @Test
    fun `your own answer is a checkbox next to the options, with its text while checked`() {
        val picked = setOf(E2E.value)
        val harness = compose.setForm(question(multi(FieldAnswer.MultipleChoice(options = picked))))

        click(FormTags.own("q1"))
        assertEquals(listOf<FormIntent>(answer("q1", FieldAnswer.MultipleChoice(picked, own = ""))), harness.intents)

        harness.intents.clear()
        harness.state.value = question(multi(FieldAnswer.MultipleChoice(picked, own = "")))
        compose.waitForIdle()
        part(FormTags.own("q1")).assertIsOn()
        part(FormTags.ownInput("q1")).performTextReplacement(OWN_TEXT)
        compose.waitForIdle()
        click(FormTags.own("q1"))

        assertEquals(
            listOf<FormIntent>(
                answer("q1", FieldAnswer.MultipleChoice(picked, own = OWN_TEXT)),
                answer("q1", FieldAnswer.MultipleChoice(picked, own = null)),
            ),
            harness.intents,
        )
    }

    // Number, yes/no, text

    @Test
    fun `a number field sends the text as typed`() {
        val harness = compose.setForm(question(number("3")))

        part(FormTags.input("copies")).assert(hasSetTextAction()).assertTextContains("3")
        part(FormTags.input("copies")).performTextReplacement("12")

        assertEquals(listOf<FormIntent>(answer("copies", FieldAnswer.Number("12"))), harness.intents)
    }

    @Test
    fun `a yes or no field is two radio buttons`() {
        val harness = compose.setForm(question(yesNo(value = false)))

        part(FormTags.yes("save")).assert(hasRole(Role.RadioButton)).assertIsNotSelected()
        part(FormTags.no("save")).assert(hasRole(Role.RadioButton)).assertIsSelected()

        click(FormTags.yes("save"))
        click(FormTags.no("save"))

        assertEquals(
            listOf<FormIntent>(answer("save", FieldAnswer.YesNo(true)), answer("save", FieldAnswer.YesNo(false))),
            harness.intents,
        )
    }

    @Test
    fun `a yes or no field with nothing picked has neither selected`() {
        compose.setForm(question(yesNo()))

        part(FormTags.yes("save")).assertIsNotSelected()
        part(FormTags.no("save")).assertIsNotSelected()
    }

    @Test
    fun `a text field shows its text and placeholder, and sends what is typed`() {
        val harness = compose.setForm(form(text()))

        // Below a label, Material shows the placeholder once the field has focus.
        part(FormTags.input("name")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(TEXT_PLACEHOLDER, useUnmergedTree = true).assertExists()
        part(FormTags.input("name")).performTextReplacement("ayşe@example.com")

        assertEquals(listOf<FormIntent>(answer("name", FieldAnswer.Text("ayşe@example.com"))), harness.intents)
    }

    // External steps

    @Test
    fun `an external step opens its page through the presenter and shows only the host`() {
        val harness = compose.setForm(form(external()))

        part(FormTags.open("login")).assert(hasRole(Role.Button)).assertIsEnabled()
        compose.onNodeWithText(EXTERNAL_HOST, substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("s3cr3t", substring = true).assertDoesNotExist()

        click(FormTags.open("login"))

        assertEquals(listOf<FormIntent>(FormIntent.OpenExternal(FORM_ID, "login")), harness.intents)
    }

    @Test
    fun `an external step that is not a web address cannot be opened`() {
        val harness = compose.setForm(form(external(url = "javascript:alert(1)")))

        absent(FormTags.open("login"))
        compose.onNodeWithText("javascript", substring = true).assertDoesNotExist()
        assertTrue(harness.intents.isEmpty())
    }

    @Test
    fun `an http address is a web address too`() {
        compose.setForm(form(external(url = "http://intranet.local/sso")))

        part(FormTags.open("login")).assertIsEnabled()
        compose.onNodeWithText("intranet.local", substring = true).performScrollTo().assertIsDisplayed()
    }

    // Unknown fields

    @Test
    fun `a field this app cannot draw says so, and its problem shows at once`() {
        compose.setForm(form(unknown()))

        part(FormTags.field("file"))
        part(FormTags.problem("file"))
        absent(FormTags.input("file"))
    }

    // Steps

    @Test
    fun `an accent strip across the top holds the heading`() {
        compose.setForm(question(single(), multi()))
        compose.waitForIdle()

        val card = compose.onNodeWithTag(CARD_TAG).fetchSemanticsNode().boundsInRoot
        val header = part(FormTags.HEADER).fetchSemanticsNode().boundsInRoot
        val heading = part(FormTags.HEADING).fetchSemanticsNode().boundsInRoot
        assertTrue(header.top - card.top < 40f, "the strip starts at the card's top")
        assertTrue(header.width > card.width * 0.8f, "the strip spans the card")
        assertTrue(header.contains(heading.center), "the heading sits in the strip")
    }

    @Test
    fun `a question with several fields shows one step at a time`() {
        val harness = compose.setForm(question(single(), multi(), yesNo()))

        part(FormTags.STEP)
        part(FormTags.field("q0"))
        absent(FormTags.field("q1"))
        absent(FormTags.field("save"))
        absent(FormTags.BACK)
        absent(FormTags.SUBMIT)
        part(FormTags.DECLINE)

        click(FormTags.NEXT)
        assertEquals(listOf<FormIntent>(FormIntent.GoToStep(FORM_ID, 1)), harness.intents)
    }

    @Test
    fun `a middle step goes back and forth`() {
        val harness = compose.setForm(question(single(), multi(), yesNo(), step = 1))

        part(FormTags.field("q1"))
        absent(FormTags.field("q0"))
        absent(FormTags.SUBMIT)

        click(FormTags.BACK)
        click(FormTags.NEXT)

        assertEquals(
            listOf<FormIntent>(FormIntent.GoToStep(FORM_ID, 0), FormIntent.GoToStep(FORM_ID, 2)),
            harness.intents,
        )
    }

    @Test
    fun `the last step sends the answers`() {
        val harness = compose.setForm(question(single(), multi(), yesNo(), step = 2))

        part(FormTags.field("save"))
        absent(FormTags.NEXT)
        part(FormTags.BACK)

        click(FormTags.SUBMIT)

        assertEquals(listOf<FormIntent>(FormIntent.Submit(FORM_ID)), harness.intents)
    }

    @Test
    fun `a question with one field has no steps`() {
        compose.setForm(question(single()))

        absent(FormTags.STEP)
        absent(FormTags.NEXT)
        absent(FormTags.BACK)
        part(FormTags.SUBMIT)
    }

    @Test
    fun `a form that is not a question shows every field at once`() {
        compose.setForm(form(text(), number(), yesNo(), external()))

        listOf("name", "copies", "save", "login").forEach { part(FormTags.field(it)) }
        absent(FormTags.STEP)
        absent(FormTags.NEXT)
        part(FormTags.SUBMIT)
    }

    // Problems

    @Test
    fun `a problem waits until the field is touched or a submit was tried`() {
        val harness = compose.setForm(form(text(problem = FieldProblem.Required), yesNo(problem = FieldProblem.Required)))

        absent(FormTags.problem("name"))
        absent(FormTags.problem("save"))

        click(FormTags.yes("save"))
        part(FormTags.problem("save"))
        absent(FormTags.problem("name"))

        harness.state.value = harness.state.value.copy(attempted = true)
        compose.waitForIdle()
        part(FormTags.problem("name"))
        part(FormTags.problem("save"))
    }

    @Test
    fun `a field with a problem is marked as an error for accessibility`() {
        compose.setForm(form(number("abc", problem = FieldProblem.NotANumber)).copy(attempted = true))

        part(FormTags.field("copies")).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test
    fun `send stays enabled with a problem left, so the presenter can show where it is`() {
        val harness = compose.setForm(form(text(problem = FieldProblem.Required)))

        click(FormTags.SUBMIT)
        click(FormTags.DECLINE)

        assertEquals(listOf(FormIntent.Submit(FORM_ID), FormIntent.Decline(FORM_ID)), harness.intents)
    }

    // Busy and errors

    @Test
    fun `while a reply is on its way both buttons wait and the card says so`() {
        val harness = compose.setForm(form(yesNo(true)).copy(submitting = true))

        part(FormTags.SUBMIT).assertIsNotEnabled()
        part(FormTags.DECLINE).assertIsNotEnabled()
        part(FormTags.PROGRESS)
        compose.onNodeWithTag(FormTags.SUBMIT).performClick()
        assertTrue(harness.intents.isEmpty())
    }

    @Test
    fun `while a decline is on its way both buttons wait`() {
        compose.setForm(form(yesNo(true)).copy(declining = true))

        part(FormTags.SUBMIT).assertIsNotEnabled()
        part(FormTags.DECLINE).assertIsNotEnabled()
        part(FormTags.PROGRESS)
    }

    @Test
    fun `a failed reply shows an error that can be dismissed`() {
        val error = FormError(FormAction.Submit, ChatFailure.Conflict)
        val harness = compose.setForm(form(yesNo(true)).copy(error = error))

        part(FormTags.ERROR)
        compose.onNode(hasContentDescription("Dismiss error")).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf<FormIntent>(FormIntent.DismissError(FORM_ID)), harness.intents)
    }

    @Test
    fun `no error, no progress while idle`() {
        compose.setForm(form(yesNo(true)))

        absent(FormTags.ERROR)
        absent(FormTags.PROGRESS)
    }

    // Heading

    @Test
    fun `a subagent's form says where it comes from`() {
        compose.setForm(form(yesNo()).copy(fromSubagent = true))

        part(FormTags.SUBAGENT)
    }

    @Test
    fun `a form of this session does not claim a subagent`() {
        compose.setForm(form(yesNo()))

        absent(FormTags.SUBAGENT)
        part(FormTags.HEADING).assertTextContains(FORM_TITLE)
    }

    // Place

    @Test
    fun `the card fits a scrolling parent capped in height`() {
        val harness = compose.setForm(form(single(), multi(), number(), yesNo(), text(), external()), maxHeight = 200.dp)

        click(FormTags.SUBMIT)

        assertEquals(listOf<FormIntent>(FormIntent.Submit(FORM_ID)), harness.intents)
    }

    // The list error

    @Test
    fun `the list error retries`() {
        val intents = compose.setListError(ChatFailure.Unreachable)

        part(FormTags.LIST_ERROR)
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf<FormIntent>(FormIntent.Retry), intents)
    }
}

/** Fields the shared support does not build. */
private object FormStateFields {
    val singleWithoutOwn = single().copy(field = singleField.copy(allowCustom = false))
}
