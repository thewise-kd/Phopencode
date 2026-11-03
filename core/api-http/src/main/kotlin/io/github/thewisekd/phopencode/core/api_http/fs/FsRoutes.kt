package io.github.thewisekd.phopencode.core.api_http.fs

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.FileSearchType
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api_http.base.ApiRequest
import io.github.thewisekd.phopencode.core.api_http.base.HttpMethod

/**
 * The request of the file name search, as recorded from opencode 2.2.5. The search text and the
 * folder go out only as encoded query parameters, never in the path.
 */
internal object FsRoutes {
    /** A read: repeatable. A limit below 1 fails with [ApiError.BadRequest] before any request. */
    fun find(location: Location, query: String, type: FileSearchType?, limit: Int?): ApiRequest {
        if (limit != null && limit < 1) throw ApiException(ApiError.BadRequest("The limit must be at least 1."))
        val parameters = buildList {
            add("query" to query)
            type?.let {
                add(
                    "type" to when (it) {
                        FileSearchType.File -> "file"
                        FileSearchType.Directory -> "directory"
                    },
                )
            }
            limit?.let { add("limit" to it.toString()) }
        }
        return ApiRequest(HttpMethod.GET, listOf("api", "fs", "find"), query = parameters, location = location)
    }
}
