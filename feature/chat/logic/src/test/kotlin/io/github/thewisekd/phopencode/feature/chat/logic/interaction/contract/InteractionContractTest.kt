package io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract

import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormOption
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The interaction contract's types: what they derive, and that printing them never shows text a
 * user typed, a model wrote or the server holds.
 */
class InteractionContractTest {
    // Assembled at runtime, so that the source holds no secret-shaped literal.
    private val secret = listOf("deploy", "with", "token", "42").joinToString("-")
    private val formId = FormId("frm_1")
    private val requestId = PermissionRequestId("per_1")
    private val child = SessionId("ses_child")
    private val message = MessageId("msg_1")

    private fun assertMasked(value: Any, vararg hidden: String) {
        val printed = value.toString()
        hidden.forEach { assertFalse(it in printed, "$printed shows a hidden value") }
    }

    private fun choice(key: String = "q0", required: Boolean = true) = FormField.SingleChoice(
        key = key,
        title = secret,
        description = secret,
        required = required,
        options = listOf(FormOption(value = secret, label = secret)),
        allowCustom = true,
    )

    // Attachments

    @Test
    fun `attachments of each kind are told apart and a file's path does not print`() {
        val attachments = listOf(
            PromptAttachment.File(secret),
            PromptAttachment.Agent("build"),
            PromptAttachment.Skill("review"),
        )
        assertEquals(attachments.size, attachments.toSet().size)
        assertEquals(PromptAttachment.File(secret), PromptAttachment.File(secret))
        assertNotEquals<PromptAttachment>(PromptAttachment.Agent("x"), PromptAttachment.Skill("x"))
        assertMasked(attachments[0], secret)
    }

    // Forms

    @Test
    fun `a form card can be submitted only with no problem left and nothing on its way`() {
        val field = FormFieldState(choice(), FieldAnswer.SingleChoice(option = secret))
        val card = FormCardState(formId = formId, sessionId = child, fields = listOf(field))

        assertTrue(card.canSubmit)
        assertFalse(card.copy(submitting = true).canSubmit)
        assertFalse(card.copy(declining = true).canSubmit)
        assertTrue(card.copy(submitting = true).busy)
        assertFalse(card.copy(fields = listOf(field.copy(problem = FieldProblem.Required))).canSubmit)
        // An unknown field type that must be answered can never be: the form can only be declined.
        val unknown = FormFieldState(
            FormField.Unknown(key = "q1", required = true, rawType = "future"),
            FieldAnswer.Unsupported,
            FieldProblem.Unsupported,
        )
        assertFalse(card.copy(fields = listOf(field, unknown)).canSubmit)
        assertTrue(card.copy(fields = listOf(field, unknown)).canDecline)
        assertFalse(card.copy(declining = true).canDecline)
    }

    @Test
    fun `steps count the fields shown`() {
        val card = FormCardState(
            formId = formId,
            sessionId = child,
            fields = listOf(
                FormFieldState(choice("q0"), FieldAnswer.SingleChoice()),
                FormFieldState(choice("q1"), FieldAnswer.SingleChoice()),
            ),
            step = 1,
        )
        assertEquals(2, card.stepCount)
        assertTrue(card.isLastStep)
        assertFalse(card.copy(step = 0).isLastStep)
        assertEquals("q1", card.currentField?.field?.key)
        assertNull(card.copy(fields = emptyList(), step = 0).currentField)
    }

    @Test
    fun `forms print no question, option or answer`() {
        val answers = listOf(
            FieldAnswer.SingleChoice(option = secret, own = secret),
            FieldAnswer.MultipleChoice(options = setOf(secret), own = secret),
            FieldAnswer.Text(secret),
            FieldAnswer.Number(secret),
            FieldAnswer.YesNo(true),
            FieldAnswer.External(opened = true),
            FieldAnswer.Unsupported,
        )
        answers.forEach { assertMasked(it, secret) }
        val field = FormFieldState(choice(), answers[0], FieldProblem.Required)
        assertMasked(field, secret)
        assertTrue("q0" in field.toString())
        val card = FormCardState(formId = formId, sessionId = child, title = secret, fields = listOf(field))
        assertMasked(card, secret)
        assertMasked(FormsState(cards = listOf(card)), secret)
        assertMasked(FormIntent.Answer(formId, "q0", answers[2]), secret)
        assertMasked(FormEvent.OpenUrl("https://example.com/$secret"), secret)
        assertEquals(card, card.copy())
        assertNotEquals(card, card.copy(title = "other"))
    }

    @Test
    fun `a failed form action names what failed`() {
        val error = FormError(FormAction.Submit, ChatFailure.Rejected)
        assertEquals(FormAction.Submit, error.action)
        assertEquals(ChatFailure.Rejected, error.reason)
        assertEquals(setOf(FormAction.Submit, FormAction.Decline), FormAction.entries.toSet())
    }

    // Permissions

    @Test
    fun `a permission can be answered once nothing is on its way`() {
        val card = PermissionCardState(requestId = requestId, sessionId = child, action = "bash")
        assertTrue(card.canReply)
        assertFalse(card.copy(replying = PermissionDecision.Once).canReply)
        assertFalse(card.fromSubagent)
    }

    @Test
    fun `permissions print no resource, message or reason`() {
        val card = PermissionCardState(
            requestId = requestId,
            sessionId = child,
            fromSubagent = true,
            action = "bash",
            resources = listOf(secret),
            message = secret,
            save = listOf(secret),
            rejecting = true,
            rejectMessage = secret,
            error = PermissionError(PermissionDecision.Reject, ChatFailure.NotFound),
        )
        assertMasked(card, secret)
        assertTrue("bash" in card.toString())
        assertMasked(PermissionsState(cards = listOf(card)), secret)
        assertMasked(PermissionIntent.RejectMessageChanged(requestId, secret), secret)
    }

    // Subagents

    @Test
    fun `running subagents are counted through the whole tree`() {
        val nested = SubagentNode(sessionId = SessionId("ses_3"), status = SubagentStatus.Running)
        val tree = listOf(
            SubagentNode(sessionId = SessionId("ses_1"), status = SubagentStatus.Succeeded, children = listOf(nested)),
            SubagentNode(sessionId = SessionId("ses_2"), status = SubagentStatus.Running),
        )
        val state = SubagentState(loading = false, subagents = tree)
        assertEquals(2, state.runningCount)
        assertEquals(3, state.totalCount)
        assertEquals(0, SubagentState().totalCount)
    }

    @Test
    fun `subagents print no title`() {
        val node = SubagentNode(sessionId = child, title = secret, tools = SubagentTools(current = secret))
        assertMasked(node, secret)
        assertMasked(SubagentState(title = secret, subagents = listOf(node)), secret)
    }

    // Palette

    @Test
    fun `the palette is open only with a trigger`() {
        assertFalse(PaletteState().open)
        assertTrue(PaletteState(trigger = PaletteTrigger.Slash).open)
        assertTrue(PaletteState(trigger = PaletteTrigger.At).open)
    }

    @Test
    fun `the palette prints no draft, query or path`() {
        val state = PaletteState(
            trigger = PaletteTrigger.At,
            query = secret,
            items = listOf(PaletteItem.File(secret)),
        )
        assertMasked(state, secret)
        assertMasked(PaletteItem.File(secret), secret)
        assertMasked(PaletteIntent.QueryChanged(secret), secret)
        assertMasked(PaletteEvent.Attached(secret, PromptAttachment.File(secret)), secret)
        assertMasked(PaletteEvent.CommandSent(secret), secret)
    }

    // Switcher

    @Test
    fun `a model option points at itself with any variant`() {
        val option = ModelOption(providerId = "anthropic", modelId = "claude", name = "Claude", variants = listOf("high"))
        assertEquals(ModelRef("anthropic", "claude"), option.ref())
        assertEquals(ModelRef("anthropic", "claude", "high"), option.ref("high"))
        assertTrue(option.isCurrent(ModelRef("anthropic", "claude", "high")))
        assertFalse(option.isCurrent(ModelRef("anthropic", "other")))
        assertFalse(option.isCurrent(null))
    }

    @Test
    fun `the switcher can switch only when enabled and idle`() {
        assertFalse(SwitcherState().canSwitch)
        assertTrue(SwitcherState(enabled = true).canSwitch)
        assertFalse(SwitcherState(enabled = true, switching = true).canSwitch)
    }

    // Revert

    @Test
    fun `a revert can be confirmed only while staged for this turn`() {
        val diff = FileDiff(VcsFile("src/a.kt", VcsFileStatus.Modified, 1, 2), patch = secret)
        val state = RevertState(loading = false, enabled = true, files = listOf(diff))
        assertTrue(state.canRevert)
        assertFalse(state.canConfirm)
        val staged = state.copy(staged = StagedRevert(message, listOf(diff), thisTurn = true))
        assertFalse(staged.canRevert)
        assertTrue(staged.canConfirm)
        assertTrue(staged.canCancel)
        assertFalse(staged.copy(pending = RevertAction.Commit).canConfirm)
        val elsewhere = state.copy(staged = StagedRevert(MessageId("msg_0"), emptyList(), thisTurn = false))
        assertFalse(elsewhere.canRevert)
        assertFalse(elsewhere.canConfirm)
        assertTrue(elsewhere.canCancel)
        assertFalse(RevertState().canRevert)
    }

    @Test
    fun `a turn's diff does not print`() {
        val diff = FileDiff(VcsFile(secret, VcsFileStatus.Added), patch = secret)
        val state = RevertState(files = listOf(diff), staged = StagedRevert(message, listOf(diff), thisTurn = true))
        assertMasked(state, secret)
    }

    // Session actions

    @Test
    fun `actions can start only when enabled and nothing runs`() {
        assertFalse(ActionsState().canAct)
        assertTrue(ActionsState(enabled = true).canAct)
        assertFalse(ActionsState(enabled = true, running = SessionAction.Compact).canAct)
        assertNotEquals<SessionAction>(SessionAction.Fork(message), SessionAction.Fork(null))
    }
}
