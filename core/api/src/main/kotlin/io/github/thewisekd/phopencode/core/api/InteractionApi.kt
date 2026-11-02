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
    /** @param sessionId Null lists the pending forms of all sessions. */
    suspend fun listForms(sessionId: SessionId? = null): List<Form>

    suspend fun replyForm(sessionId: SessionId, formId: FormId, answers: FormAnswers)

    /** Declines the form. */
    suspend fun cancelForm(sessionId: SessionId, formId: FormId)

    /** @param sessionId Null lists the pending requests of all sessions. */
    suspend fun listPermissions(sessionId: SessionId? = null): List<PermissionRequest>

    /** @param message Tells the model why, on a rejection. */
    suspend fun replyPermission(
        sessionId: SessionId,
        requestId: PermissionRequestId,
        decision: PermissionDecision,
        message: String? = null,
    )
}
