package io.github.thewisekd.phopencode.core.files

import io.github.thewisekd.phopencode.core.testing.api.FakeFsApi
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteFiles
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSftpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Content search run by this machine's `sh` against a temporary folder. `PATH` holds only a
 * folder of chosen tools, so a test decides whether `rg` exists; the `rg` here is a fake that
 * records its arguments, because a real one may be missing.
 */
class ContentSearchShellTest {
    private val top: Path = Files.createTempDirectory("phopencode-files-search").toRealPath()
    private val work: Path = top.resolve("work").createDirectories()
    private val bin: Path = top.resolve("bin").createDirectories()
    private val remote = FakeRemoteFiles()
    private val sftp = FakeSftpClient(work.toString(), remote)

    private val hostileNames = listOf(
        "\$(touch pwned).txt",
        "`touch pwned2`.txt",
        "-rf",
        "--help",
        "a b.txt",
        "it's \"q\".txt",
        "new\nline.txt",
        "çğıöşü İI.txt",
        "a:1:b.txt",
        "semi;colon&amp|pipe.txt",
    )

    @AfterTest
    fun tearDown() {
        top.toFile().deleteRecursively()
    }

    private fun tool(name: String) {
        if (Files.exists(bin.resolve(name), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        val path = ProcessBuilder("/bin/sh", "-c", "command -v $name").start().inputStream.readAllBytes().decodeToString().trim()
        check(path.startsWith("/")) { "This machine has no $name" }
        Files.createSymbolicLink(bin.resolve(name), Path.of(path))
    }

    private fun script(name: String, body: String) {
        val file = bin.resolve(name)
        file.writeText("#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
    }

    private fun service(environment: Map<String, String> = emptyMap()): Pair<FileService, LocalShellExec> {
        val exec = LocalShellExec(top, mapOf("PATH" to bin.toString(), "LC_ALL" to "C.UTF-8") + environment)
        return RemoteFileService(sftp, exec, FakeFsApi()) to exec
    }

    private fun folder(relative: String): Path {
        val path = work.resolve(relative).createDirectories()
        remote.addDirectory(path.toString())
        return path
    }

    private fun noInjection() {
        val found = top.toFile().walk().filter { it.name == "pwned" || it.name == "pwned2" }.toList()
        assertTrue(found.isEmpty(), "A name ran as a command")
    }

    @Test
    fun `grep finds hostile names when rg is missing`() = runBlocking {
        tool("head")
        tool("grep")
        val dir = folder("src")
        hostileNames.forEach { dir.resolve(it).writeText("first\nthe needle: here\n") }
        dir.resolve("other.txt").writeText("nothing\n")
        val (service, _) = service()

        val result = service.searchContent(dir.toString(), ContentQuery("needle:"))
        assertEquals(SearchTool.Grep, result.tool)
        assertEquals(hostileNames.map { "$dir/$it" }.toSet(), result.matches.map { it.path }.toSet())
        assertTrue(result.matches.all { it.line == 2 && it.text == "the needle: here" })
        assertFalse(result.truncated)
        assertFalse(result.incomplete)
        noInjection()
    }

    @Test
    fun `a hostile folder name and hostile queries stay single words`() = runBlocking {
        tool("head")
        tool("grep")
        val dir = folder("\$(touch pwned) -rf 'x'\nçİ")
        dir.resolve("f.txt").writeText("-rf\n\$(touch pwned2)\nit's\n`touch pwned`\n")
        val (service, _) = service()
        listOf("-rf" to 1, "\$(touch pwned2)" to 2, "it's" to 3, "`touch pwned`" to 4).forEach { (query, line) ->
            val matches = service.searchContent(dir.toString(), ContentQuery(query)).matches
            assertEquals(listOf(ContentMatch("$dir/f.txt", line, query)), matches, query)
        }
        noInjection()
    }

    @Test
    fun `grep skips the git folder and binary files, and honours case and regex options`() = runBlocking {
        tool("head")
        tool("grep")
        val dir = folder("p")
        dir.resolve(".git").createDirectories().resolve("config").writeText("Needle\n")
        dir.resolve("bin.dat").writeBytes("Needle\u0000\u0001".encodeToByteArray())
        dir.resolve("a.txt").writeText("Needle\nneedle\nneedles\n")
        val (service, _) = service()
        assertEquals(listOf(2, 3), service.searchContent(dir.toString(), ContentQuery("needle")).matches.map { it.line })
        assertEquals(
            listOf(1, 2, 3),
            service.searchContent(dir.toString(), ContentQuery("needle", ignoreCase = true)).matches.map { it.line },
        )
        assertEquals(
            listOf(2),
            service.searchContent(dir.toString(), ContentQuery("^needle$", regex = true)).matches.map { it.line },
        )
    }

    @Test
    fun `results are limited per search and per file`() = runBlocking {
        tool("head")
        tool("grep")
        val dir = folder("many")
        (1..5).forEach { dir.resolve("f$it.txt").writeText("hit\n".repeat(10)) }
        val (service, _) = service()

        val limited = service.searchContent(dir.toString(), ContentQuery("hit", maxResults = 7, maxMatchesPerFile = 3))
        assertEquals(7, limited.matches.size)
        assertTrue(limited.truncated)
        assertTrue(limited.matches.groupBy { it.path }.values.all { it.size <= 3 })

        val bytes = service.searchContent(dir.toString(), ContentQuery("hit", maxOutputBytes = 200))
        assertTrue(bytes.truncated)
        assertTrue(bytes.matches.isNotEmpty() && bytes.matches.size < 50)
        assertTrue(bytes.matches.all { it.text == "hit" })
    }

    @Test
    fun `the per-file limit alone marks the result truncated`() = runBlocking {
        tool("head")
        tool("grep")
        val dir = folder("perfile")
        dir.resolve("a.txt").writeText("hit\n".repeat(10))
        val (service, _) = service()
        val result = service.searchContent(dir.toString(), ContentQuery("hit", maxResults = 100, maxMatchesPerFile = 3))
        assertEquals(3, result.matches.size)
        assertTrue(result.truncated)

        val below = service.searchContent(dir.toString(), ContentQuery("hit", maxResults = 100, maxMatchesPerFile = 20))
        assertEquals(10, below.matches.size)
        assertFalse(below.truncated)
    }

    @Test
    fun `a shortened line marks the result truncated`() = runBlocking {
        tool("head")
        tool("grep")
        val dir = folder("long")
        dir.resolve("a.txt").writeText("hit" + "x".repeat(100) + "\n")
        val (service, _) = service()
        val result = service.searchContent(dir.toString(), ContentQuery("hit", maxLineChars = 10))
        assertEquals("hitxxxxxxx", result.matches.single().text)
        assertTrue(result.truncated)
    }

    private fun killedRg(output: ByteArray, signal: String): FileService {
        tool("head")
        tool("cat")
        val file = top.resolve("killed-out")
        file.writeBytes(output)
        script("rg", "cat \"\$RG_OUT\"\nkill -s $signal \$\$")
        return service(mapOf("RG_OUT" to file.toString())).first
    }

    @Test
    fun `a search killed by a signal with no matches is a failed search`() = runBlocking {
        val dir = folder("d")
        val service = killedRg(ByteArray(0), "KILL")
        val error = runCatching { service.searchContent(dir.toString(), ContentQuery("x")) }.exceptionOrNull()
        assertEquals(FileError.SearchFailed, (error as FileException).error)
    }

    @Test
    fun `a search killed after some matches is incomplete, not merely truncated`() = runBlocking {
        val dir = folder("d")
        val service = killedRg("$dir/a".encodeToByteArray() + byteArrayOf(0) + "1:x\n".encodeToByteArray(), "KILL")
        val result = service.searchContent(dir.toString(), ContentQuery("x"))
        assertEquals(1, result.matches.size)
        assertTrue(result.incomplete)
        assertFalse(result.truncated)
    }

    @Test
    fun `SIGPIPE below the output cap is not a truncation`() = runBlocking {
        val dir = folder("d")
        val service = killedRg("$dir/a".encodeToByteArray() + byteArrayOf(0) + "1:x\n".encodeToByteArray(), "PIPE")
        val result = service.searchContent(dir.toString(), ContentQuery("x"))
        assertTrue(result.incomplete)
        assertFalse(result.truncated)
        val empty = killedRg(ByteArray(0), "PIPE")
        val error = runCatching { empty.searchContent(dir.toString(), ContentQuery("x")) }.exceptionOrNull()
        assertEquals(FileError.SearchFailed, (error as FileException).error)
    }

    @Test
    fun `SIGPIPE after head filled the cap is a truncation`() = runBlocking {
        val dir = folder("d")
        val record = "$dir/a".encodeToByteArray() + byteArrayOf(0) + "1:x\n".encodeToByteArray()
        val output = (1..100).fold(ByteArray(0)) { all, _ -> all + record }
        tool("head")
        tool("cat")
        val file = top.resolve("big-out")
        file.writeBytes(output)
        // Writes until head closes the pipe, so the shell dies of SIGPIPE.
        script("rg", "while :; do cat \"\$RG_OUT\" || kill -s PIPE \$\$; done")
        val (service, _) = service(mapOf("RG_OUT" to file.toString()))
        val result = service.searchContent(dir.toString(), ContentQuery("x", maxOutputBytes = record.size * 10L + 3))
        assertEquals(10, result.matches.size)
        assertTrue(result.truncated)
        assertFalse(result.incomplete)
    }

    @Test
    fun `rg is used when present, with every argument intact`() = runBlocking {
        tool("head")
        tool("grep")
        val dir = folder("-rf dir \$(x)")
        val record = top.resolve("rg-args")
        val output = top.resolve("rg-output")
        output.writeBytes("$dir/a:1.txt".encodeToByteArray() + byteArrayOf(0) + "4:it's -e here\n".encodeToByteArray())
        tool("cat")
        script("rg", "for a in \"\$@\"; do printf '%s\\0' \"\$a\"; done > \"\$RG_ARGS\"\ncat \"\$RG_OUT\"\nexit 0")
        val (service, _) = service(mapOf("RG_ARGS" to record.toString(), "RG_OUT" to output.toString()))

        val query = "it's -e \$(touch pwned)"
        val result = service.searchContent(dir.toString(), ContentQuery(query, maxMatchesPerFile = 9))
        assertEquals(SearchTool.Ripgrep, result.tool)
        assertEquals(listOf(ContentMatch("$dir/a:1.txt", 4, "it's -e here")), result.matches)

        val args = record.readBytes().decodeToString().split('\u0000').dropLast(1)
        assertEquals(listOf("--", dir.toString()), args.takeLast(2))
        assertEquals(query, args[args.indexOf("-e") + 1])
        assertTrue("--fixed-strings" in args && "--null" in args && "--no-config" in args && "--max-count=9" in args)
        assertFalse("--ignore-case" in args)
        noInjection()
    }

    @Test
    fun `rg failing with no output is a failed search`() = runBlocking {
        tool("head")
        val dir = folder("d")
        script("rg", "exit 2")
        val (service, _) = service()
        val error = runCatching { service.searchContent(dir.toString(), ContentQuery("(", regex = true)) }.exceptionOrNull()
        assertEquals(FileError.SearchFailed, (error as FileException).error)
    }

    @Test
    fun `rg failing after matches gives an incomplete result`() = runBlocking {
        tool("head")
        val dir = folder("d")
        val output = top.resolve("out")
        output.writeBytes("$dir/a".encodeToByteArray() + byteArrayOf(0) + "1:x\n".encodeToByteArray())
        tool("cat")
        script("rg", "cat \"\$RG_OUT\"\nexit 2")
        val (service, _) = service(mapOf("RG_OUT" to output.toString()))
        val result = service.searchContent(dir.toString(), ContentQuery("x"))
        assertTrue(result.incomplete)
        assertEquals(1, result.matches.size)
    }

    @Test
    fun `no match is an empty result`() = runBlocking {
        tool("head")
        tool("grep")
        val dir = folder("empty")
        dir.resolve("a").writeText("x\n")
        val (service, _) = service()
        val result = service.searchContent(dir.toString(), ContentQuery("absent"))
        assertTrue(result.matches.isEmpty())
        assertFalse(result.truncated || result.incomplete)
    }

    @Test
    fun `no head is a clear error, not an empty search`() = runBlocking {
        tool("grep")
        val dir = folder("d")
        dir.resolve("a").writeText("x\n")
        val (service, _) = service()
        val error = runCatching { service.searchContent(dir.toString(), ContentQuery("x")) }.exceptionOrNull()
        assertEquals(FileError.SearchUnavailable, (error as FileException).error)
    }

    @Test
    fun `a failing head with nothing let through is a failed search`() = runBlocking {
        tool("grep")
        script("head", "exit 1")
        val dir = folder("d")
        dir.resolve("a").writeText("x\n")
        val (service, _) = service()
        val error = runCatching { service.searchContent(dir.toString(), ContentQuery("x")) }.exceptionOrNull()
        assertEquals(FileError.SearchFailed, (error as FileException).error)
    }

    @Test
    fun `a head failing after some matches gives an incomplete result`() = runBlocking {
        tool("grep")
        tool("cat")
        script("head", "cat\nexit 3")
        val dir = folder("d")
        dir.resolve("a").writeText("x\n")
        val (service, _) = service()
        val result = service.searchContent(dir.toString(), ContentQuery("x"))
        assertEquals(listOf(ContentMatch("$dir/a", 1, "x")), result.matches)
        assertTrue(result.incomplete)
    }

    @Test
    fun `neither rg nor grep is a clear error`() = runBlocking {
        tool("head")
        val dir = folder("d")
        val (service, _) = service()
        val error = runCatching { service.searchContent(dir.toString(), ContentQuery("x")) }.exceptionOrNull()
        assertEquals(FileError.SearchUnavailable, (error as FileException).error)
    }

    @Test
    fun `the folder is checked before any command runs`() = runBlocking {
        tool("head")
        tool("grep")
        work.resolve("file").writeText("x\n")
        remote.addFile(work.resolve("file").toString(), "x\n".encodeToByteArray())
        val (service, exec) = service()
        suspend fun error(path: String) =
            (runCatching { service.searchContent(path, ContentQuery("x")) }.exceptionOrNull() as FileException).error
        assertEquals(FileError.NotADirectory, error("file"))
        assertEquals(FileError.NotFound, error("missing"))
        assertEquals(FileError.PathRejected, error("../.."))
        assertEquals(FileError.PathRejected, error("/etc"))
        assertTrue(exec.commands.isEmpty())
        assertFalse(top.resolve("pwned").exists())
    }
}
