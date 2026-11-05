package io.github.thewisekd.phopencode.feature.chat.ui.form

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldProblem
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishFormTest : FormLanguageContract() {
    override val strings = ExpectedFormStrings(
        questionHeading = "The model asks",
        formHeading = "Input needed",
        fromSubagent = "From a subagent",
        step = { step, count -> "Question $step of $count" },
        required = "Required",
        ownAnswer = "Your own answer",
        ownAnswerField = "Type your answer",
        answerField = "Your answer",
        yes = "Yes",
        no = "No",
        choicesOneToTwo = "Pick 1 to 2.",
        numberWholeOneToTen = "Between 1 and 10 · Whole number",
        externalOpen = "Open the page",
        externalHost = { "Opens $it in the browser." },
        externalOpened = "Opened. Finish the step on the page, then send.",
        externalBlocked = "This link cannot be opened: only web addresses are allowed.",
        unsupported = "This app cannot show this field.",
        problems = mapOf(
            FieldProblem.Required to "This field is required.",
            FieldProblem.TooFewChoices to "Pick at least 1 option.",
            FieldProblem.TooManyChoices to "Pick at most 2 options.",
            FieldProblem.NotANumber to "Enter a number.",
            FieldProblem.NotAnInteger to "Enter a whole number.",
            FieldProblem.BelowMinimum to "Enter 1 or more.",
            FieldProblem.AboveMaximum to "Enter 10 or less.",
            FieldProblem.TooShort to "Enter at least 3 characters.",
            FieldProblem.TooLong to "Enter at most 5 characters.",
            FieldProblem.WrongFormat to "The answer is not in the expected format.",
            FieldProblem.NotOpened to "Open the page first.",
            FieldProblem.Unsupported to "This field cannot be answered here, so the form can only be declined.",
        ),
        back = "Back",
        next = "Next",
        submit = "Send",
        decline = "Decline",
        sending = "Sending the answer…",
        declining = "Declining…",
        declineQuestionHint = "Declining stops the current run.",
        submitFailed = { "The answer could not be sent. $it" },
        declineFailed = { "The form could not be declined. $it" },
        reasons = mapOf(
            ChatFailure.NotConnected to "There is no connection to the server.",
            ChatFailure.Unreachable to "The server could not be reached. Check the connection and try again.",
            ChatFailure.Unauthorized to "The server refused the credentials.",
            ChatFailure.NotFound to "The form no longer exists.",
            ChatFailure.Conflict to "The form was already answered or closed.",
            ChatFailure.Rejected to "The server refused the answer.",
            ChatFailure.ServerFailed to "The server ran into an error.",
            ChatFailure.Unknown to "An unexpected error occurred.",
        ),
        listError = { "The subagents' questions could not be loaded. $it" },
        retry = "Try again",
    )
}
