package io.github.thewisekd.phopencode.core.api_http.vcs

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.VcsApi
import io.github.thewisekd.phopencode.core.api.VcsBranch
import io.github.thewisekd.phopencode.core.api.VcsBranchInfo
import io.github.thewisekd.phopencode.core.api.VcsDiffQuery
import io.github.thewisekd.phopencode.core.api_http.base.DataEnvelope
import io.github.thewisekd.phopencode.core.api_http.base.HttpTransport
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.VcsFile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * [VcsApi] over the opencode 2.2 HTTP API. Requests are in [VcsRoutes], replies in `VcsWire.kt`;
 * errors are mapped by [HttpTransport]. A reply that does not fit the recorded shapes fails with
 * `ApiError.MalformedResponse`.
 *
 * Diff replies are taken whole, as the contract says; the server already caps the patch text it puts
 * in one reply. Nothing here logs a folder, a query or file content: the transport logs the route only.
 *
 * It holds nothing but [transport], so closing the transport closes it.
 */
internal class HttpVcsApi(private val transport: HttpTransport) : VcsApi {
    /**
     * `/api/vcs`, then `/api/vcs/base`, one after the other. The server answers the base route with
     * [ApiError.BadRequest] when it cannot infer a review base ("Choose a review base"), which here
     * means no default branch, not a failure; every other error fails the call.
     */
    override suspend fun current(location: Location): VcsBranchInfo {
        val info = transport.receive(VcsRoutes.info(location), INFO).data
        val base = try {
            transport.receive(VcsRoutes.base(location), BASE).data
        } catch (e: ApiException) {
            if (e.error !is ApiError.BadRequest) throw e
            null
        }
        return VcsBranchInfo(branch = info.branch.current, defaultBranch = base?.name)
    }

    override suspend fun status(location: Location): List<VcsFile> =
        transport.receive(VcsRoutes.status(location), STATUS).data.map(VcsFileStatusWire::toModel)

    override suspend fun diff(location: Location, query: VcsDiffQuery): List<FileDiff> =
        transport.receive(VcsRoutes.diff(location, query), DIFF).data.map(VcsFileDiffWire::toModel)

    override suspend fun branches(location: Location, search: String?, limit: Int?): List<VcsBranch> =
        transport.receive(VcsRoutes.branches(location, search, limit), BRANCHES).data.map(::VcsBranch)

    override fun toString(): String = "HttpVcsApi"

    private companion object {
        val INFO = DataEnvelope.serializer(VcsInfoWire.serializer())
        val BASE = DataEnvelope.serializer(VcsBaseWire.serializer().nullable)
        val STATUS = DataEnvelope.serializer(ListSerializer(VcsFileStatusWire.serializer()))
        val DIFF = DataEnvelope.serializer(ListSerializer(VcsFileDiffWire.serializer()))
        val BRANCHES = DataEnvelope.serializer(ListSerializer(String.serializer()))
    }
}
