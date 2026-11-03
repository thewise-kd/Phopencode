package io.github.thewisekd.phopencode.core.api_http.fs

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.FileSearchType
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.api_http.base.apiError
import io.github.thewisekd.phopencode.core.api_http.base.assertApiError
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FsApiBehaviorTest {
    private val project = Location("/srv/project")

    private fun envelope(data: String) = """{"location":{"directory":"/srv/project"},"data":$data}"""

    private suspend fun sentQuery(call: suspend HttpFsApi.() -> Unit): List<Pair<String, String?>> =
        TestServer().use { t ->
            t.enqueue(body = envelope("[]"))
            HttpFsApi(t.transport).call()
            val url = t.server.takeRequest().url
            assertEquals(listOf("api", "fs", "find"), url.pathSegments)
            (0 until url.querySize).map { url.queryParameterName(it) to url.queryParameterValue(it) }
        }

    private val location = "location[directory]" to "/srv/project"

    @Test
    fun `type and limit are sent only when given`() = runTest {
        assertEquals(listOf("query" to "main", location), sentQuery { find(project, "main") })
        assertEquals(
            listOf("query" to "main", "type" to "file", location),
            sentQuery { find(project, "main", FileSearchType.File) },
        )
        assertEquals(
            listOf("query" to "main", "type" to "directory", "limit" to "3", location),
            sentQuery { find(project, "main", FileSearchType.Directory, limit = 3) },
        )
    }

    @Test
    fun `a limit below one is refused before any request`() = runTest {
        TestServer().use { t ->
            listOf(0, -1).forEach { limit ->
                assertIs<ApiError.BadRequest>(apiError { HttpFsApi(t.transport).find(project, "x", limit = limit) })
            }
            assertEquals(0, t.server.requestCount)
        }
    }

    @Test
    fun `a search query and a folder travel as exact query values`() = runTest {
        val hostile = listOf(
            "..", "../../etc/passwd", "a/../b", "%2e%2e", "%2E%2e/x", "with space", "what?", "#frag", "a&b=c",
            "x=y", "çalışma/ğüşıöç", "日本語", "emoji 😀", "+plus", "%", "%%20", "\\back", "/",
        )
        for (text in hostile) {
            val folder = Location("/srv/$text")
            TestServer().use { t ->
                t.enqueue(body = envelope("[]"))
                HttpFsApi(t.transport).find(folder, text)
                val url = t.server.takeRequest().url
                assertEquals(listOf("api", "fs", "find"), url.pathSegments, text)
                assertEquals(listOf("query", "location[directory]"), url.queryParameterNames.toList(), text)
                assertEquals(text, url.queryParameter("query"), text)
                assertEquals(folder.directory, url.queryParameter("location[directory]"), text)
            }
        }
    }

    @Test
    fun `logs name the route only, never the query or the folder`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = envelope("""[{"path":"secret-result.txt","type":"file"}]"""))
            HttpFsApi(t.transport).find(Location("/srv/secret-folder"), "secret-query")
            val logged = t.logger.lines.joinToString("\n")
            assertTrue(logged.contains("/api/fs/find"), logged)
            listOf("secret-folder", "secret-query", "secret-result").forEach { assertFalse(logged.contains(it), logged) }
        }
    }

    @Test
    fun `entries map their type, drop a directory's trailing slash, and keep unknown types raw`() = runTest {
        TestServer().use { t ->
            t.enqueue(
                body = envelope(
                    """[{"path":"src/main.kt","type":"file"},
                       {"path":"src/main/","type":"directory"},
                       {"path":"docs/çalışma notları/","type":"directory"},
                       {"path":"link","type":"symlink","extra":1}]""",
                ),
            )
            assertEquals(
                listOf(
                    FileEntry("src/main.kt", FileEntryType.File),
                    FileEntry("src/main", FileEntryType.Directory),
                    FileEntry("docs/çalışma notları", FileEntryType.Directory),
                    FileEntry("link", FileEntryType.Unknown("symlink")),
                ),
                HttpFsApi(t.transport).find(project, "main"),
            )
        }
    }

    @Test
    fun `the root keeps its slash`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = envelope("""[{"path":"/","type":"directory"}]"""))
            assertEquals(listOf(FileEntry("/", FileEntryType.Directory)), HttpFsApi(t.transport).find(project, "x"))
        }
    }

    @Test
    fun `server errors keep their type`() = runTest {
        TestServer().use { t ->
            t.enqueue(status = 404, body = """{"_tag":"LocationNotFoundError","message":"no such folder"}""")
            assertApiError(ApiError.NotFound) { HttpFsApi(t.transport).find(project, "x") }
        }
    }

    @Test
    fun `replies that break the spec are malformed`() = runTest {
        val broken = listOf(
            """[{"type":"file"}]""",
            """[{"path":"a"}]""",
            """[{"path":7,"type":"file"}]""",
            """{"path":"a","type":"file"}""",
            """null""",
        )
        for (data in broken) {
            TestServer().use { t ->
                t.enqueue(body = envelope(data))
                assertApiError(ApiError.MalformedResponse) { HttpFsApi(t.transport).find(project, "x") }
            }
        }
    }
}
