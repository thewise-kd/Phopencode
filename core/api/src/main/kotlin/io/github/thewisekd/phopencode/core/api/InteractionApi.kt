package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormAnswers
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionId

/**
 * What the server is waiting on the user for: forms, which include the model's questions, and
 * permissions. Errors are described on [OpencodeClient].
 *
 * Both may belong to a child session; pass the session the item itself names.
 */
interface InteractionApi {
    /**
     * @param sessionId Null lists the pending forms of all sessions in [location].
     * @param location Used with a null [sessionId] only; null there is the server's default folder.
     */
    suspend fun listForms(sessionId: SessionId? = null, location: Location? = null): List<Form>

    /**
     * An invalid answer fails with [ApiError.BadRequest], and a form that is already settled with
     * [ApiError.Conflict]. An `external` field is answered with `true` after the app opened its URL.
     */
    suspend fun replyForm(sessionId: SessionId, formId: FormId, answers: FormAnswers)

    /** Declines the form. Dismissing a question ends the whole execution, not just the question. */
    suspend fun cancelForm(sessionId: SessionId, formId: FormId)

    /**
     * @param sessionId Null lists the pending requests of all sessions in [location].
     * @param location Used with a null [sessionId] only; null there is the server's default folder.
     */
    suspend fun listPermissions(sessionId: SessionId? = null, location: Location? = null): List<PermissionRequest>

    /**
     * Replying twice fails with [ApiError.NotFound], not [ApiError.Conflict]. The `always` decision
     * saves a rule at the scope of [PermissionRequest.save], which can be far wider than this request.
     *
     * @param message Tells the model why, on a rejection.
     */
    suspend fun replyPermission(
        sessionId: SessionId,
        requestId: PermissionRequestId,
        decision: PermissionDecision,
        message: String? = null,
    )
}
