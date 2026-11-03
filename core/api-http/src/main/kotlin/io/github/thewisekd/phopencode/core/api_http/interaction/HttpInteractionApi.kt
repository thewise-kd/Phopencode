package io.github.thewisekd.phopencode.core.api_http.interaction

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.InteractionApi
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api_http.base.ApiRequest
import io.github.thewisekd.phopencode.core.api_http.base.DataEnvelope
import io.github.thewisekd.phopencode.core.api_http.base.HttpMethod
import io.github.thewisekd.phopencode.core.api_http.base.HttpTransport
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.FormAnswers
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/**
 * [InteractionApi] over `opencode serve`'s form and permission routes.
 *
 * | Call | Route | Repeatable |
 * |---|---|---|
 * | [listForms] | `GET /api/session/{s}/form`, or `GET /api/form` by folder | yes |
 * | [replyForm] | `POST /api/session/{s}/form/{f}/reply` | no |
 * | [cancelForm] | `DELETE /api/session/{s}/form/{f}` | no |
 * | [listPermissions] | `GET /api/session/{s}/permission`, or `GET /api/permission/request` by folder | yes |
 * | [replyPermission] | `POST /api/session/{s}/permission/{r}/reply` | no |
 *
 * The session routes take no location: the session knows its folder. Lists only read, so OkHttp
 * may resend them after a dropped connection. Replies and cancels settle an item once: a repeat
 * fails (409 for a form, 404 for a permission) and so would report an error for a call that
 * worked, and a cancelled question ends the whole execution. They are sent at most once, and
 * fail with [ApiError.Network] when their connection drops, even if the server got them: the
 * caller can list the pending items to see whether they were settled.
 *
 * Nothing here logs: the transport's log line has the method and path only, never an answer, a
 * permission pattern or a folder.
 */
internal class HttpInteractionApi(private val transport: HttpTransport) : InteractionApi {
    override suspend fun listForms(sessionId: SessionId?, location: Location?): List<Form> =
        list(sessionId, location, route = "form", folderRoute = listOf("api", "form")).map(::formOf)

    override suspend fun replyForm(sessionId: SessionId, formId: FormId, answers: FormAnswers) {
        // Built before the call, so an answer that cannot be sent fails without reaching the server.
        val body = replyBody(answers)
        transport.execute(
            ApiRequest(
                method = HttpMethod.POST,
                path = sessionPath(sessionId, "form", formId.value, "reply"),
                body = body,
                repeatable = false,
            ),
        )
    }

    override suspend fun cancelForm(sessionId: SessionId, formId: FormId) {
        transport.execute(
            ApiRequest(HttpMethod.DELETE, sessionPath(sessionId, "form", formId.value), repeatable = false),
        )
    }

    override suspend fun listPermissions(sessionId: SessionId?, location: Location?): List<PermissionRequest> =
        list(sessionId, location, route = "permission", folderRoute = listOf("api", "permission", "request"))
            .map(::permissionRequestOf)

    override suspend fun replyPermission(
        sessionId: SessionId,
        requestId: PermissionRequestId,
        decision: PermissionDecision,
        message: String?,
    ) {
        transport.execute(
            ApiRequest(
                method = HttpMethod.POST,
                path = sessionPath(sessionId, "permission", requestId.value, "reply"),
                body = permissionReplyBody(decision, message),
                repeatable = false,
            ),
        )
    }

    /** A session's items, or a folder's when [sessionId] is null; [location] is used only then. */
    private suspend fun list(
        sessionId: SessionId?,
        location: Location?,
        route: String,
        folderRoute: List<String>,
    ): List<JsonObject> {
        val request = if (sessionId != null) {
            ApiRequest(HttpMethod.GET, sessionPath(sessionId, route))
        } else {
            ApiRequest(HttpMethod.GET, folderRoute, location = location)
        }
        return transport.receive(request, DataEnvelope.serializer(ListSerializer(JsonObject.serializer()))).data
    }

    private fun sessionPath(sessionId: SessionId, vararg route: String): List<String> =
        listOf("api", "session", sessionId.value) + route

    override fun toString(): String = "HttpInteractionApi"
}
