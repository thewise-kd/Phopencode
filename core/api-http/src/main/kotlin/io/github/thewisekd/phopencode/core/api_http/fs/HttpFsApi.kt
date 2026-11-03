package io.github.thewisekd.phopencode.core.api_http.fs

import io.github.thewisekd.phopencode.core.api.FileSearchType
import io.github.thewisekd.phopencode.core.api.FsApi
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api_http.base.DataEnvelope
import io.github.thewisekd.phopencode.core.api_http.base.HttpTransport
import io.github.thewisekd.phopencode.core.model.FileEntry
import kotlinx.serialization.builtins.ListSerializer

/**
 * [FsApi] over the opencode 2.2 HTTP API: `GET /api/fs/find`, as recorded from 2.2.5. Errors are
 * mapped by [HttpTransport]; a reply that does not fit the recorded shape fails with
 * `ApiError.MalformedResponse`.
 *
 * The search text and the folder go out only as encoded query parameters, never in the path; the
 * transport logs the route only.
 *
 * It holds nothing but [transport], so closing the transport closes it.
 */
internal class HttpFsApi(private val transport: HttpTransport) : FsApi {
    override suspend fun find(
        location: Location,
        query: String,
        type: FileSearchType?,
        limit: Int?,
    ): List<FileEntry> =
        transport.receive(FsRoutes.find(location, query, type, limit), ENTRIES).data.map(FsEntryWire::toModel)

    override fun toString(): String = "HttpFsApi"

    private companion object {
        val ENTRIES = DataEnvelope.serializer(ListSerializer(FsEntryWire.serializer()))
    }
}
