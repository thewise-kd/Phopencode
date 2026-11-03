package io.github.thewisekd.phopencode.core.api_http.vcs

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.UntrackedMode
import io.github.thewisekd.phopencode.core.api.VcsBranch
import io.github.thewisekd.phopencode.core.api.VcsBranchInfo
import io.github.thewisekd.phopencode.core.api.VcsDiffMode
import io.github.thewisekd.phopencode.core.api.VcsDiffQuery
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.api_http.base.apiError
import io.github.thewisekd.phopencode.core.api_http.base.assertApiError
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl

class VcsApiBehaviorTest {
    private val project = Location("/srv/project")

    private fun envelope(data: String) = """{"location":{"directory":"/srv/project"},"data":$data}"""

    /** The query of the one request [call] sent, decoded, in order. */
    private suspend fun queryOf(reply: String, call: suspend HttpVcsApi.() -> Unit): List<Pair<String, String?>> =
        TestServer().use { t ->
            t.enqueue(body = envelope(reply))
            HttpVcsApi(t.transport).call()
            val url: HttpUrl = t.server.takeRequest().url
            (0 until url.querySize).map { url.queryParameterName(it) to url.queryParameterValue(it) }
        }

    private val location = "location[directory]" to "/srv/project"

    @Test
    fun `every diff mode goes out by its wire name`() = runTest {
        val sent = VcsDiffMode.entries.associateWith { mode ->
            queryOf("[]") { diff(project, VcsDiffQuery(mode = mode)) }
        }
        assertEquals(
            mapOf(
                VcsDiffMode.Working to listOf("mode" to "working", location),
                VcsDiffMode.Branch to listOf("mode" to "branch", location),
                VcsDiffMode.Committed to listOf("mode" to "committed", location),
            ),
            sent,
        )
    }

    @Test
    fun `a diff sends base and context when given, and nothing for null`() = runTest {
        assertEquals(
            listOf("mode" to "committed", "base" to "origin/main", "context" to "0", location),
            queryOf("[]") { diff(project, VcsDiffQuery(VcsDiffMode.Committed, base = "origin/main", context = 0)) },
        )
        assertEquals(listOf("mode" to "working", location), queryOf("[]") { diff(project, VcsDiffQuery()) })
    }

    @Test
    fun `every untracked mode goes out by its wire name, and null sends nothing`() = runTest {
        val sent = UntrackedMode.entries.associateWith { mode ->
            queryOf("[]") { diff(project, VcsDiffQuery(untracked = mode)) }
        }
        assertEquals(
            mapOf(
                UntrackedMode.Files to listOf("mode" to "working", "untracked" to "files", location),
                UntrackedMode.Folders to listOf("mode" to "working", "untracked" to "folders", location),
            ),
            sent,
        )
        assertEquals(
            listOf("mode" to "working", location),
            queryOf("[]") { diff(project, VcsDiffQuery(untracked = null)) },
        )
    }

    /**
     * With `folders` the server collapses an untracked folder into one entry and may mark entries with
     * `more: true`. The model has no place for the marker, so it is dropped and the entry still decodes.
     */
    @Test
    fun `a folders diff with more markers decodes and drops the marker`() = runTest {
        TestServer().use { t ->
            t.enqueue(
                body = envelope(
                    """[{"file":"build/","patch":"","additions":0,"deletions":0,"status":"untracked","more":true},""" +
                        """{"file":"a.txt","patch":"x","additions":1,"deletions":0,"status":"added"}]""",
                ),
            )
            val diff = HttpVcsApi(t.transport).diff(project, VcsDiffQuery(untracked = UntrackedMode.Folders))
            assertEquals(
                listOf(
                    FileDiff(VcsFile("build/", VcsFileStatus.Untracked, 0, 0), patch = ""),
                    FileDiff(VcsFile("a.txt", VcsFileStatus.Added, 1, 0), patch = "x"),
                ),
                diff,
            )
            assertEquals("folders", t.server.takeRequest().url.queryParameter("untracked"))
        }
    }

    @Test
    fun `a negative context is refused before any request`() = runTest {
        TestServer().use { t ->
            val error = apiError { HttpVcsApi(t.transport).diff(project, VcsDiffQuery(context = -1)) }
            assertIs<ApiError.BadRequest>(error)
            assertEquals(0, t.server.requestCount)
        }
    }

    @Test
    fun `a limit below one is refused before any request`() = runTest {
        TestServer().use { t ->
            listOf(0, -5).forEach { limit ->
                assertIs<ApiError.BadRequest>(apiError { HttpVcsApi(t.transport).branches(project, limit = limit) })
            }
            assertEquals(0, t.server.requestCount)
        }
    }

    @Test
    fun `branches send search and limit as query parameters`() = runTest {
        assertEquals(
            listOf("search" to "feature", "limit" to "2", location),
            queryOf("[]") { branches(project, search = "feature", limit = 2) },
        )
        assertEquals(listOf(location), queryOf("[]") { branches(project) })
    }

    /** Text a user types: none of it may change the route or leak into another parameter. */
    private val hostile = listOf(
        "..", "../..", "a/../b", "%2e%2e", "%2E%2e/x", "with space", "what?", "#frag", "a&b=c", "x=y",
        "çalışma/ğüşıöç", "日本語", "emoji 😀", "+plus", "%", "%%20", "\\back",
    )

    @Test
    fun `search, base and location travel as exact query values`() = runTest {
        for (text in hostile) {
            val folder = Location("/srv/$text")
            TestServer().use { t ->
                t.enqueue(body = envelope("[]"))
                t.enqueue(body = envelope("[]"))
                val api = HttpVcsApi(t.transport)
                api.branches(folder, search = text)
                api.diff(folder, VcsDiffQuery(VcsDiffMode.Branch, base = text))

                val branches = t.server.takeRequest().url
                assertEquals(listOf("api", "vcs", "branch"), branches.pathSegments, text)
                assertEquals(listOf("search", "location[directory]"), branches.queryParameterNames.toList(), text)
                assertEquals(text, branches.queryParameter("search"), text)
                assertEquals(folder.directory, branches.queryParameter("location[directory]"), text)

                val diff = t.server.takeRequest().url
                assertEquals(listOf("api", "vcs", "diff"), diff.pathSegments, text)
                assertEquals(listOf("mode", "base", "location[directory]"), diff.queryParameterNames.toList(), text)
                assertEquals(text, diff.queryParameter("base"), text)
                assertEquals(folder.directory, diff.queryParameter("location[directory]"), text)
            }
        }
    }

    @Test
    fun `logs name the route only, never the query`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = envelope("[]"))
            t.enqueue(body = envelope("[]"))
            val api = HttpVcsApi(t.transport)
            api.branches(Location("/srv/secret-folder"), search = "secret-search")
            api.diff(Location("/srv/secret-folder"), VcsDiffQuery(base = "secret-base"))
            val logged = t.logger.lines.joinToString("\n")
            assertTrue(logged.contains("/api/vcs/branch"), logged)
            listOf("secret-folder", "secret-search", "secret-base").forEach { assertFalse(logged.contains(it), logged) }
        }
    }

    @Test
    fun `current makes both calls for the same folder`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = envelope("""{"provider":"git","branch":{"current":"feature/x","default":"main"}}"""))
            t.enqueue(body = envelope("""{"name":"develop","ref":"refs/heads/develop","source":"reflog"}"""))
            val info = HttpVcsApi(t.transport).current(project)
            assertEquals(VcsBranchInfo(branch = "feature/x", defaultBranch = "develop"), info)
            val paths = List(2) { t.server.takeRequest().url }.map { it.encodedPath to it.queryParameter("location[directory]") }
            assertEquals(setOf("/api/vcs" to "/srv/project", "/api/vcs/base" to "/srv/project"), paths.toSet())
        }
    }

    @Test
    fun `a failure of either call fails current`() = runTest {
        TestServer().use { t ->
            t.enqueue(status = 503, body = """{"_tag":"ServiceUnavailableError","message":"starting"}""")
            t.enqueue(body = envelope("null"))
            assertApiError(ApiError.Server(503, "starting")) { HttpVcsApi(t.transport).current(project) }
        }
        TestServer().use { t ->
            t.enqueue(body = envelope("""{"branch":{"current":"main"}}"""))
            t.enqueue(status = 503, body = """{"_tag":"ServiceUnavailableError","message":"starting"}""")
            assertApiError(ApiError.Server(503, "starting")) { HttpVcsApi(t.transport).current(project) }
        }
    }

    /** The server answers this way when it cannot infer a review base ("Choose a review base"). */
    @Test
    fun `a review base the server cannot infer is no default branch`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = envelope("""{"provider":"git","branch":{"current":"feature/x"}}"""))
            t.enqueue(status = 400, body = """{"_tag":"InvalidRequestError","message":"Choose a review base"}""")
            assertEquals(VcsBranchInfo(branch = "feature/x"), HttpVcsApi(t.transport).current(project))
        }
    }

    @Test
    fun `server errors keep their type`() = runTest {
        TestServer().use { t ->
            t.enqueue(status = 404, body = """{"_tag":"LocationNotFoundError","message":"no such folder"}""")
            assertApiError(ApiError.NotFound) { HttpVcsApi(t.transport).status(project) }
        }
    }

    @Test
    fun `statuses the spec lacks are kept raw, the known ones are mapped`() = runTest {
        TestServer().use { t ->
            t.enqueue(
                body = envelope(
                    """[{"file":"a","additions":0,"deletions":3,"status":"deleted"},
                       {"file":"b","additions":2,"deletions":1,"status":"modified"},
                       {"file":"c","additions":1,"deletions":1,"status":"renamed"},
                       {"file":"d","additions":0,"deletions":0,"status":"copied","extra":true}]""",
                ),
            )
            assertEquals(
                listOf(
                    VcsFile("a", VcsFileStatus.Deleted, 0, 3),
                    VcsFile("b", VcsFileStatus.Modified, 2, 1),
                    VcsFile("c", VcsFileStatus.Renamed, 1, 1),
                    VcsFile("d", VcsFileStatus.Unknown("copied"), 0, 0),
                ),
                HttpVcsApi(t.transport).status(project),
            )
        }
    }

    @Test
    fun `a diff with no patch and absent counts still decodes`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = envelope("""[{"file":"image.png","status":"modified","more":true}]"""))
            assertEquals(
                listOf(FileDiff(VcsFile("image.png", VcsFileStatus.Modified), patch = null)),
                HttpVcsApi(t.transport).diff(project),
            )
        }
    }

    @Test
    fun `replies that break the spec are malformed`() = runTest {
        val broken = listOf(
            "status" to """[{"additions":1,"deletions":0,"status":"added"}]""",
            "status" to """[{"file":"a","additions":-1,"deletions":0,"status":"added"}]""",
            "status" to """[{"file":"a","additions":1.5,"deletions":0,"status":"added"}]""",
            "status" to """[{"file":"a","additions":1e999999999,"deletions":0,"status":"added"}]""",
            "status" to """[{"file":"a","additions":99999999999999999999999,"deletions":0,"status":"added"}]""",
            "status" to """{"file":"a"}""",
            "diff" to """[{"file":"a","patch":"x","additions":1,"deletions":-2,"status":"added"}]""",
            "diff" to """[{"file":"a","patch":"x","additions":1,"deletions":0}]""",
            "branches" to """[1, {"name":"x"}]""",
            "branches" to """{"main":true}""",
        )
        for ((call, data) in broken) {
            TestServer().use { t ->
                t.enqueue(body = envelope(data))
                val api = HttpVcsApi(t.transport)
                assertApiError(ApiError.MalformedResponse) {
                    when (call) {
                        "status" -> api.status(project)
                        "diff" -> api.diff(project)
                        else -> api.branches(project)
                    }
                }
            }
        }
    }

    @Test
    fun `branch names come back as given`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = envelope("""["main","origin/main","feature/çalış"]"""))
            assertEquals(
                listOf(VcsBranch("main"), VcsBranch("origin/main"), VcsBranch("feature/çalış")),
                HttpVcsApi(t.transport).branches(project),
            )
        }
    }

    @Test
    fun `a branch object with neither name is no branch`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = envelope("""{"provider":"git","branch":{}}"""))
            t.enqueue(body = envelope("null"))
            assertEquals(VcsBranchInfo(), HttpVcsApi(t.transport).current(project))
        }
    }
}
