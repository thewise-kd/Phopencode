package io.github.thewisekd.phopencode.core.testing.api

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.InteractionApi
import io.github.thewisekd.phopencode.core.api.InterruptReason
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.FieldCondition
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormAnswers
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormKind
import io.github.thewisekd.phopencode.core.model.FormState
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionEffect
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.PermissionRule
import io.github.thewisekd.phopencode.core.model.SessionId
import java.math.BigDecimal

/** A reply that [InteractionApi.replyPermission] received. */
data class PermissionReply(
    val requestId: PermissionRequestId,
    val decision: PermissionDecision,
    val message: String?,
)

/**
 * Pending forms and permissions in memory. Answers are checked against the form's fields as the
 * server checks them, so a screen that sends a bad answer fails here too.
 *
 * @property defaultDirectory The server's default folder, which a list with neither a session nor a
 *   location covers. A session with no directory counts as being in it.
 */
class FakeInteractionApi(
    private val control: FakeApiControl = FakeApiControl(),
    private val sessions: FakeSessionApi = FakeSessionApi(control),
    val defaultDirectory: String = "/fake/default",
) : InteractionApi {
    private class FormEntry(val form: Form, var state: FormState = FormState.Pending)

    private val forms = LinkedHashMap<FormId, FormEntry>()
    private val permissions = LinkedHashMap<PermissionRequestId, PermissionRequest>()
    private val replies = mutableListOf<PermissionReply>()
    private val rules = mutableListOf<PermissionRule>()

    init {
        // The recordings do not cover deleting a session with pending items: this is the fake's choice.
        sessions.onDelete { id ->
            forms.values.removeAll { it.form.sessionId == id }
            permissions.values.removeAll { it.sessionId == id }
        }
    }

    /** The replies so far, oldest first. */
    val permissionReplies: List<PermissionReply>
        get() = sessions.withLock { replies.toList() }

    /** What `always` decisions saved so far. */
    val savedRules: List<PermissionRule>
        get() = sessions.withLock { rules.toList() }

    /** Adds a pending form. Sends no event: emit [ServerEvent.FormCreated] to announce it. */
    fun seedForm(form: Form) {
        sessions.withLock { forms[form.id] = FormEntry(form) }
    }

    /** Adds a pending request. Sends no event: emit [ServerEvent.PermissionAsked] to announce it. */
    fun seedPermission(request: PermissionRequest) {
        sessions.withLock { permissions[request.id] = request }
    }

    /** What the detail route would say about a form, or null if there is no such form. */
    fun stateOf(id: FormId): FormState? = sessions.withLock { forms[id]?.state }

    private fun folderOf(sessionId: SessionId) = sessions.peek(sessionId)?.directory ?: defaultDirectory

    private fun selects(owner: SessionId, sessionId: SessionId?, location: Location?): Boolean =
        if (sessionId != null) owner == sessionId else folderOf(owner) == (location?.directory ?: defaultDirectory)

    override suspend fun listForms(sessionId: SessionId?, location: Location?): List<Form> =
        control.call("interactions.listForms") {
            sessions.serving {
                forms.values
                    .filter { it.state == FormState.Pending && selects(it.form.sessionId, sessionId, location) }
                    .map { it.form.copy(state = null) }
            }
        }

    private fun pending(sessionId: SessionId, formId: FormId): FormEntry {
        val entry = forms[formId]?.takeIf { it.form.sessionId == sessionId } ?: throw ApiException(ApiError.NotFound)
        if (entry.state != FormState.Pending) throw ApiException(ApiError.Conflict("The form is already settled"))
        return entry
    }

    override suspend fun replyForm(sessionId: SessionId, formId: FormId, answers: FormAnswers): Unit =
        control.call("interactions.replyForm") {
            sessions.serving {
                val entry = pending(sessionId, formId)
                entry.form.check(answers)
                entry.state = FormState.Answered(answers)
                sessions.events.emit(ServerEvent.FormReplied(sessionId, formId, answers))
            }
        }

    override suspend fun cancelForm(sessionId: SessionId, formId: FormId): Unit =
        control.call("interactions.cancelForm") {
            sessions.serving {
                val entry = pending(sessionId, formId)
                entry.state = FormState.Cancelled()
                sessions.events.emit(ServerEvent.FormCancelled(sessionId, formId))
                if (entry.form.kind == FormKind.Question) sessions.interrupt(sessionId, InterruptReason.Shutdown)
            }
        }

    override suspend fun listPermissions(sessionId: SessionId?, location: Location?): List<PermissionRequest> =
        control.call("interactions.listPermissions") {
            sessions.serving { permissions.values.filter { selects(it.sessionId, sessionId, location) } }
        }

    override suspend fun replyPermission(
        sessionId: SessionId,
        requestId: PermissionRequestId,
        decision: PermissionDecision,
        message: String?,
    ): Unit = control.call("interactions.replyPermission") {
        sessions.serving {
            val request = permissions[requestId]?.takeIf { it.sessionId == sessionId }
                ?: throw ApiException(ApiError.NotFound)
            permissions.remove(requestId)
            replies += PermissionReply(requestId, decision, message)
            if (decision == PermissionDecision.Always) {
                rules += request.save.map { PermissionRule(request.action, it, PermissionEffect.Allow) }
            }
            sessions.events.emit(ServerEvent.PermissionReplied(sessionId, requestId, decision))
        }
    }
}

/** The detail is fixed text: a server's reply may not quote the answer back through a fake either. */
private fun invalid(): Nothing = throw ApiException(ApiError.BadRequest("The answer is not valid for this form"))

private fun Form.check(answers: FormAnswers) {
    if (answers.keys.any { key -> fields.none { it.key == key } }) invalid()
    fields.forEach { field ->
        val answer = answers[field.key]?.takeUnless { it == DataValue.Null }
        when {
            field is FormField.External -> if (answer != DataValue.Bool(true)) invalid()
            field !is FormField.Input -> Unit
            answer != null -> if (!field.accepts(answer)) invalid()
            field.required && !field.hidden && field.visibleWith(answers) -> invalid()
        }
    }
}

private fun FormField.Input.visibleWith(answers: FormAnswers): Boolean = visibleWhen.all { condition ->
    when (condition) {
        is FieldCondition.Equals -> answers[condition.key] == condition.value
        is FieldCondition.NotEquals -> answers[condition.key] != condition.value
        is FieldCondition.Unknown -> false
    }
}

private fun FormField.Input.accepts(answer: DataValue): Boolean = when (this) {
    is FormField.SingleChoice -> answer is DataValue.Text && (allowCustom || options.any { it.value == answer.value })
    is FormField.MultipleChoice -> accepts(answer)
    is FormField.FreeText -> answer is DataValue.Text && accepts(answer.value)
    is FormField.Number -> answer is DataValue.Num && accepts(answer.value)
    is FormField.YesNo -> answer is DataValue.Bool
    is FormField.Unknown -> true
}

private fun FormField.MultipleChoice.accepts(answer: DataValue): Boolean {
    val picks = (answer as? DataValue.Items)?.values ?: return false
    val offered = options.map { it.value }
    return picks.all { it is DataValue.Text && (allowCustom || it.value in offered) } &&
        (minSelected == null || picks.size >= (minSelected ?: 0)) &&
        (maxSelected == null || picks.size <= (maxSelected ?: Int.MAX_VALUE))
}

private fun FormField.FreeText.accepts(text: String): Boolean =
    (minLength == null || text.length >= (minLength ?: 0)) &&
        (maxLength == null || text.length <= (maxLength ?: Int.MAX_VALUE)) &&
        (pattern?.let { Regex(it).matches(text) } ?: true)

private fun FormField.Number.accepts(number: BigDecimal): Boolean =
    (!integerOnly || number.stripTrailingZeros().scale() <= 0) &&
        (min?.let { number >= it } ?: true) &&
        (max?.let { number <= it } ?: true)
