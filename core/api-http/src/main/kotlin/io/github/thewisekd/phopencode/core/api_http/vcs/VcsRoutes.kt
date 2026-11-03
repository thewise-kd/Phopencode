package io.github.thewisekd.phopencode.core.api_http.vcs

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.UntrackedMode
import io.github.thewisekd.phopencode.core.api.VcsDiffMode
import io.github.thewisekd.phopencode.core.api.VcsDiffQuery
import io.github.thewisekd.phopencode.core.api_http.base.ApiRequest
import io.github.thewisekd.phopencode.core.api_http.base.HttpMethod

/**
 * The requests of the VCS routes, as recorded from opencode 2.2.5. All of them read only, so all are
 * repeatable.
 *
 * Every value a user may type, a branch search or a diff base, goes out as an encoded query
 * parameter, as does the folder: none becomes part of the path. A value this module refuses fails
 * with [ApiError.BadRequest] before any request, and the error names no value.
 */
internal object VcsRoutes {
    private val VCS = listOf("api", "vcs")

    fun info(location: Location) = ApiRequest(HttpMethod.GET, VCS, location = location)

    fun base(location: Location) = ApiRequest(HttpMethod.GET, VCS + "base", location = location)

    fun status(location: Location) = ApiRequest(HttpMethod.GET, VCS + "status", location = location)

    /** `untracked` goes out as `files` or `folders`, the server's names; null sends nothing. */
    fun diff(location: Location, query: VcsDiffQuery): ApiRequest {
        query.context?.let { if (it < 0) refuse("The diff context must not be negative.") }
        val untracked = when (query.untracked) {
            null -> null
            UntrackedMode.Files -> "files"
            UntrackedMode.Folders -> "folders"
        }
        val parameters = buildList {
            add(
                "mode" to when (query.mode) {
                    VcsDiffMode.Working -> "working"
                    VcsDiffMode.Branch -> "branch"
                    VcsDiffMode.Committed -> "committed"
                },
            )
            query.base?.let { add("base" to it) }
            query.context?.let { add("context" to it.toString()) }
            untracked?.let { add("untracked" to it) }
        }
        return ApiRequest(HttpMethod.GET, VCS + "diff", query = parameters, location = location)
    }

    fun branches(location: Location, search: String?, limit: Int?): ApiRequest {
        if (limit != null && limit < 1) refuse("The limit must be at least 1.")
        val parameters = buildList {
            search?.let { add("search" to it) }
            limit?.let { add("limit" to it.toString()) }
        }
        return ApiRequest(HttpMethod.GET, VCS + "branch", query = parameters, location = location)
    }

    private fun refuse(reason: String): Nothing = throw ApiException(ApiError.BadRequest(reason))
}
