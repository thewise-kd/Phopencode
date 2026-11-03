package io.github.thewisekd.phopencode.core.api_http.session

import io.github.thewisekd.phopencode.core.api.CreateSessionRequest
import io.github.thewisekd.phopencode.core.api.InboxItem
import io.github.thewisekd.phopencode.core.api.Page
import io.github.thewisekd.phopencode.core.api.SessionApi
import io.github.thewisekd.phopencode.core.api.SessionQuery
import io.github.thewisekd.phopencode.core.api.SessionUpdate
import io.github.thewisekd.phopencode.core.api_http.base.DataEnvelope
import io.github.thewisekd.phopencode.core.api_http.base.HttpTransport
import io.github.thewisekd.phopencode.core.api_http.base.PageEnvelope
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionId
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * [SessionApi] over the opencode 2.2 HTTP API. Requests, and which of them may be repeated, are in
 * [SessionRoutes]; errors are mapped by [HttpTransport]. A reply that does not fit the recorded shapes
 * fails with `ApiError.MalformedResponse`.
 *
 * It holds nothing but [transport], so closing the transport closes it.
 */
internal class HttpSessionApi(private val transport: HttpTransport) : SessionApi {
    override suspend fun create(request: CreateSessionRequest): Session =
        transport.receive(SessionRoutes.create(request), SESSION).data.toModel()

    override suspend fun list(query: SessionQuery): Page<Session> =
        transport.receive(SessionRoutes.list(query), SESSIONS).toPage { it.toModel() }

    override suspend fun get(id: SessionId): Session =
        transport.receive(SessionRoutes.get(id), SESSION).data.toModel()

    override suspend fun delete(id: SessionId) {
        transport.execute(SessionRoutes.delete(id))
    }

    override suspend fun update(id: SessionId, update: SessionUpdate) {
        transport.execute(SessionRoutes.update(id, update))
    }

    override suspend fun stop(id: SessionId): Boolean =
        transport.receive(SessionRoutes.stop(id), InterruptWire.serializer()).interrupted

    override suspend fun fork(id: SessionId, before: MessageId?): Session =
        transport.receive(SessionRoutes.fork(id, before), SESSION).data.toModel()

    override suspend fun compact(id: SessionId): InboxItem =
        transport.receive(SessionRoutes.compact(id), INBOX_ITEM).data.toModel()

    override suspend fun activeStatuses(): Map<SessionId, SessionActivity> =
        transport.receive(SessionRoutes.active(), ACTIVE).data
            .entries.associate { (id, activity) -> SessionId(id) to activity.toModel() }

    override fun toString(): String = "HttpSessionApi"

    private companion object {
        val SESSION = DataEnvelope.serializer(SessionWire.serializer())
        val SESSIONS = PageEnvelope.serializer(SessionWire.serializer())
        val INBOX_ITEM = DataEnvelope.serializer(InboxItemWire.serializer())
        val ACTIVE = DataEnvelope.serializer(MapSerializer(String.serializer(), ActiveWire.serializer()))
    }
}
