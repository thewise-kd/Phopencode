package io.github.thewisekd.phopencode.core.git.command

import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.quote.ShellQuote
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/**
 * Proves what reaches git, word for word: the built command line runs through a real `/bin/sh`, and
 * a stand-in `git` on `PATH` records its arguments, its environment and its stdin.
 */
class GitCommandLineTest {
    @TempDir
    lateinit var tmp: Path

    private val hostileDir = "/srv/\$(touch pwned) dir/-rf/it's \"q\"/new\nline/çğıöşü"

    private class Delivered(val args: List<String>, val env: Map<String, String>, val stdin: ByteArray) {
        /** The git arguments after the global options and `-C <directory>`. */
        val gitArgs: List<String> get() = args.drop(args.indexOf("-C") + 2)
    }

    private fun deliver(command: GitCommand): Delivered {
        val bin = tmp.resolve("bin").createDirectories()
        val out = tmp.resolve("out").createDirectories()
        val cwd = tmp.resolve("cwd").createDirectories()
        val script = bin.resolve("git")
        script.writeText(
            """
            #!/bin/sh
            for a in "${'$'}@"; do printf '%s\0' "${'$'}a"; done > "${'$'}OUT/args"
            env > "${'$'}OUT/env"
            cat > "${'$'}OUT/stdin"
            """.trimIndent() + "\n",
        )
        script.setPosixFilePermissions(PosixFilePermissions.fromString("rwx------"))
        val exec = LocalShellExec(
            cwd,
            mapOf(
                "PATH" to bin.absolutePathString() + ":" + (System.getenv("PATH") ?: "/usr/bin:/bin"),
                "OUT" to out.absolutePathString(),
            ),
        )
        val result = runBlocking { GitRunner(exec).run(command) }
        assertTrue(result.succeeded)
        val args = String(out.resolve("args").readBytes(), Charsets.UTF_8).split('\u0000').dropLast(1)
        val env = out.resolve("env").readText().lines().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        assertTrue(tmp.resolve("cwd").toFile().list()!!.isEmpty(), "Something ran in the shell")
        return Delivered(args, env, out.resolve("stdin").readBytes())
    }

    @Test
    fun `the command line is nothing but quoted words`() {
        val command = GitCommands.add(hostileDir, listOf("-rf", "\$(touch pwned).txt"))
        assertEquals(ShellQuote.join(command.argv), command.commandLine)
    }

    @Test
    fun `the directory and the global options reach git unchanged`() {
        val delivered = deliver(GitCommands.status(hostileDir))
        assertEquals(
            listOf(
                "--no-pager",
                "--literal-pathspecs",
                "-c", "core.quotePath=false",
                "-c", "color.ui=false",
                "-c", "core.fsmonitor=false",
                "-c", "credential.interactive=never",
                "-c", "log.showSignature=false",
                "-C", hostileDir,
            ),
            delivered.args.take(delivered.args.indexOf("-C") + 2),
        )
    }

    @Test
    fun `every command gets an environment that cannot prompt, page or colour`() {
        val delivered = deliver(GitCommands.push(hostileDir, "origin", "main"))
        val expected = mapOf(
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_ASKPASS" to "false",
            "SSH_ASKPASS" to "false",
            "SSH_ASKPASS_REQUIRE" to "never",
            "GIT_EDITOR" to "false",
            "GIT_SEQUENCE_EDITOR" to "false",
            "GIT_MERGE_AUTOEDIT" to "no",
            "GIT_PAGER" to "cat",
            "PAGER" to "cat",
            "LC_ALL" to "C",
        )
        expected.forEach { (key, value) -> assertEquals(value, delivered.env[key], key) }
        assertNull(delivered.env["GIT_OPTIONAL_LOCKS"], "A write must take its locks")
        assertEquals(0, delivered.stdin.size)
    }

    @Test
    fun `read-only commands do not take optional locks`() {
        val readOnly = listOf(
            GitCommands.status(hostileDir),
            GitCommands.diff(hostileDir, staged = true),
            GitCommands.log(hostileDir, format = "%H", maxCount = 10),
            GitCommands.show(hostileDir, "HEAD"),
            GitCommands.branchList(hostileDir, format = "%(refname)"),
            GitCommands.revParseToplevel(hostileDir),
            GitCommands.remoteList(hostileDir),
            GitCommands.show(hostileDir, "HEAD", output = ShowOutput.NameStatus, firstParent = true),
            GitCommands.diffUntracked(hostileDir, "a.txt"),
            GitCommands.untrackedFolderCheck(hostileDir, "a/b.txt"),
        )
        readOnly.forEach { command ->
            assertTrue(command.readOnly)
            assertEquals("0", deliver(command).env["GIT_OPTIONAL_LOCKS"])
            assertEquals("0", deliver(command).env["GIT_TERMINAL_PROMPT"])
        }
    }

    @Test
    fun `nothing but a commit has stdin`() {
        val commands = listOf(
            GitCommands.status(hostileDir),
            GitCommands.add(hostileDir, listOf("a")),
            GitCommands.fetch(hostileDir),
            GitCommands.mergeFastForward(hostileDir),
            GitCommands.push(hostileDir, "origin", "main"),
            GitCommands.switchBranch(hostileDir, "main"),
        )
        commands.forEach { assertNull(it.toExecRequest().stdin) }
    }

    @Test
    fun `status arguments`() {
        assertEquals(
            listOf("status", "--porcelain=v2", "-z", "--branch", "--ahead-behind", "--find-renames", "--untracked-files=all", "--"),
            deliver(GitCommands.status(hostileDir)).gitArgs,
        )
        assertEquals(
            listOf("status", "--porcelain=v2", "-z", "--branch", "--ahead-behind", "--find-renames", "--untracked-files=no", "--", "-rf", "a b"),
            deliver(GitCommands.status(hostileDir, includeUntracked = false, paths = listOf("-rf", "a b"))).gitArgs,
        )
    }

    private val stable = listOf("--no-relative", "--find-renames")
    private val patch = stable + listOf("--src-prefix=a/", "--dst-prefix=b/", "--full-index", "--submodule=short")

    @Test
    fun `diff arguments`() {
        val base = listOf("diff", "--no-color", "--no-ext-diff", "--no-textconv")
        assertEquals(
            base + patch + listOf("--unified=3", "--", "--all", "new\nline"),
            deliver(GitCommands.diff(hostileDir, staged = false, paths = listOf("--all", "new\nline"))).gitArgs,
        )
        assertEquals(
            base + listOf("--cached") + stable + listOf("--numstat", "-z", "--"),
            deliver(GitCommands.diff(hostileDir, staged = true, output = DiffOutput.NumStat)).gitArgs,
        )
        assertEquals(
            base + stable + listOf("--name-status", "-z", "--"),
            deliver(GitCommands.diff(hostileDir, staged = false, output = DiffOutput.NameStatus)).gitArgs,
        )
        assertEquals(
            base + listOf("--cached") + patch + listOf("--unified=0", "--"),
            deliver(GitCommands.diff(hostileDir, staged = true, contextLines = 0)).gitArgs,
        )
    }

    @Test
    fun `log arguments`() {
        val format = "%H%x00%s %(trailers) \$(touch pwned)"
        assertEquals(
            listOf("log", "-z", "--no-color", "--no-show-signature", "--encoding=UTF-8", "--format=$format", "--max-count=20", "--skip=40", "--"),
            deliver(GitCommands.log(hostileDir, format = format, maxCount = 20, skip = 40)).gitArgs,
        )
        assertEquals(
            listOf("log", "-z", "--no-color", "--no-show-signature", "--encoding=UTF-8", "--format=%H", "--max-count=5", "--skip=0", "--all", "--", "-rf"),
            deliver(GitCommands.log(hostileDir, format = "%H", maxCount = 5, allRefs = true, paths = listOf("-rf"))).gitArgs,
        )
        assertEquals(
            listOf("log", "-z", "--no-color", "--no-show-signature", "--encoding=UTF-8", "--format=%H", "--max-count=5", "--skip=0", "origin/main..feature/ç", "--"),
            deliver(GitCommands.log(hostileDir, format = "%H", maxCount = 5, revision = "origin/main..feature/ç")).gitArgs,
        )
    }

    @Test
    fun `show arguments`() {
        val base = listOf("show", "--no-color", "--no-ext-diff", "--no-textconv", "--encoding=UTF-8")
        assertEquals(
            base + listOf("--format=") + patch + listOf("--unified=3", "abc123", "--", "-rf"),
            deliver(GitCommands.show(hostileDir, "abc123", paths = listOf("-rf"))).gitArgs,
        )
        assertEquals(
            base + listOf("--format=%H%x00%B") + stable + listOf("--name-status", "-z", "HEAD~1", "--"),
            deliver(GitCommands.show(hostileDir, "HEAD~1", format = "%H%x00%B", output = ShowOutput.NameStatus)).gitArgs,
        )
        assertEquals(
            base + listOf("--format=%H", "-s", "HEAD", "--"),
            deliver(GitCommands.show(hostileDir, "HEAD", format = "%H", output = ShowOutput.None)).gitArgs,
        )
        assertEquals(
            base + listOf("--format=") + stable + listOf("--numstat", "-z", "HEAD", "--"),
            deliver(GitCommands.show(hostileDir, "HEAD", output = ShowOutput.NumStat)).gitArgs,
        )
    }

    @Test
    fun `show asks for the first parent's diff only when told to`() {
        val base = listOf("show", "--no-color", "--no-ext-diff", "--no-textconv", "--encoding=UTF-8", "--format=")
        val firstParent = "--diff-merges=first-parent"
        assertEquals(
            base + patch + listOf("--unified=3", firstParent, "abc123", "--", "-rf"),
            deliver(GitCommands.show(hostileDir, "abc123", paths = listOf("-rf"), firstParent = true)).gitArgs,
        )
        assertEquals(
            base + stable + listOf("--numstat", "-z", firstParent, "HEAD", "--"),
            deliver(GitCommands.show(hostileDir, "HEAD", output = ShowOutput.NumStat, firstParent = true)).gitArgs,
        )
        assertEquals(
            base + stable + listOf("--name-status", "-z", firstParent, "HEAD", "--"),
            deliver(GitCommands.show(hostileDir, "HEAD", output = ShowOutput.NameStatus, firstParent = true)).gitArgs,
        )
        // The default is unchanged: no merge option at all.
        assertFalse(GitCommands.show(hostileDir, "HEAD").argv.any { it.startsWith("--diff-merges") })
    }

    @Test
    fun `an untracked file's diff compares the path with an empty file, without the index`() {
        val noIndex = listOf("diff", "--no-index", "--no-color", "--no-ext-diff", "--no-textconv")
        assertEquals(
            noIndex + listOf("--src-prefix=a/", "--dst-prefix=b/", "--full-index", "--unified=3", "--", "/dev/null", "-rf/\$(touch pwned)\nx"),
            deliver(GitCommands.diffUntracked(hostileDir, "-rf/\$(touch pwned)\nx")).gitArgs,
        )
        assertEquals(
            noIndex + listOf("--src-prefix=a/", "--dst-prefix=b/", "--full-index", "--unified=0", "--", "/dev/null", "--all"),
            deliver(GitCommands.diffUntracked(hostileDir, "--all", contextLines = 0)).gitArgs,
        )
        assertEquals(
            noIndex + listOf("--numstat", "-z", "--", "/dev/null", "a b.txt"),
            deliver(GitCommands.diffUntracked(hostileDir, "a b.txt", output = DiffOutput.NumStat)).gitArgs,
        )
        val nested = deliver(GitCommands.untrackedFolderCheck(hostileDir, "-rf/x/y.txt"))
        assertEquals("$hostileDir/-rf/x", nested.args[nested.args.indexOf("-C") + 1])
        assertEquals(listOf("rev-parse", "--show-toplevel", "--show-prefix"), nested.gitArgs)
        val top = deliver(GitCommands.untrackedFolderCheck(hostileDir, "y.txt"))
        assertEquals(hostileDir, top.args[top.args.indexOf("-C") + 1])
        assertEquals(listOf("rev-parse", "--show-toplevel", "--show-prefix"), top.gitArgs)
    }

    @Test
    fun `branch and repository queries`() {
        val base = listOf("branch", "--list", "--no-color", "--no-column", "--format=%(refname)")
        assertEquals(base, deliver(GitCommands.branchList(hostileDir, format = "%(refname)")).gitArgs)
        assertEquals(
            base + "-r",
            deliver(GitCommands.branchList(hostileDir, format = "%(refname)", scope = BranchScope.Remote)).gitArgs,
        )
        assertEquals(
            base + "-a",
            deliver(GitCommands.branchList(hostileDir, format = "%(refname)", scope = BranchScope.All)).gitArgs,
        )
        assertEquals(listOf("rev-parse", "--show-toplevel"), deliver(GitCommands.revParseToplevel(hostileDir)).gitArgs)
        assertEquals(listOf("remote"), deliver(GitCommands.remoteList(hostileDir)).gitArgs)
    }

    @Test
    fun `index and work tree changes put every path after the separator`() {
        val paths = listOf("-rf", "--all", "\$(touch pwned).txt", "new\nline.txt", ":(top)x")
        assertEquals(listOf("add", "--") + paths, deliver(GitCommands.add(hostileDir, paths)).gitArgs)
        assertEquals(listOf("reset", "-q", "--") + paths, deliver(GitCommands.unstage(hostileDir, paths)).gitArgs)
        assertEquals(listOf("checkout-index", "-f", "-u", "--") + paths, deliver(GitCommands.discardWorktree(hostileDir, paths)).gitArgs)
        val check = listOf("status", "--porcelain=v2", "-z", "--no-renames", "--untracked-files=all", "--ignored=matching", "--")
        // Always the whole work tree.
        assertEquals(check, deliver(GitCommands.discardCheck(hostileDir, paths)).gitArgs)
        assertEquals(check, deliver(GitCommands.discardCheck(hostileDir, listOf("-rf/x", "a/b/c"))).gitArgs)
        val prefix = deliver(GitCommands.directoryPrefix(hostileDir, "-rf/x"))
        assertEquals("$hostileDir/-rf/x", prefix.args[prefix.args.indexOf("-C") + 1])
        assertEquals(listOf("rev-parse", "--show-prefix"), prefix.gitArgs)
        assertTrue(GitCommands.discardCheck(hostileDir, paths).readOnly)
        val probe = deliver(GitCommands.directoryProbe(hostileDir, "-rf"))
        assertEquals("$hostileDir/-rf", probe.args[probe.args.indexOf("-C") + 1])
        assertEquals(listOf("version"), probe.gitArgs)
    }

    @Test
    fun `the parsers' formats pass through unchanged`() {
        val logFormat = "%H%x00%P%x00%an%x00%ae%x00%at%x00%cn%x00%ce%x00%ct%x00%s%x00%b"
        val log = deliver(GitCommands.log(hostileDir, format = logFormat, maxCount = 50)).gitArgs
        assertTrue("--format=$logFormat" in log)
        assertTrue("--no-show-signature" in log)
        assertTrue("--encoding=UTF-8" in log)
        val branchFormat = "%(refname)%00%(HEAD)%00%(objectname)%00%(upstream)%00%(upstream:track,nobracket)%00%(symref)"
        assertTrue("--format=$branchFormat" in deliver(GitCommands.branchList(hostileDir, format = branchFormat)).gitArgs)
    }

    /** One command from every builder, with every option that changes its words. */
    private fun everyBuilder(dir: String): List<GitCommand> = listOf(
        GitCommands.status(dir),
        GitCommands.status(dir, includeUntracked = false, paths = listOf("a")),
        GitCommands.diff(dir, staged = false),
        GitCommands.diff(dir, staged = true, paths = listOf("a"), output = DiffOutput.NumStat),
        GitCommands.diff(dir, staged = true, output = DiffOutput.NameStatus),
        GitCommands.log(dir, format = "%H", maxCount = 1, skip = 1, revision = "HEAD", allRefs = true, paths = listOf("a")),
        GitCommands.show(dir, "HEAD"),
        GitCommands.show(dir, "HEAD", output = ShowOutput.None),
        GitCommands.show(dir, "HEAD", output = ShowOutput.NumStat),
        GitCommands.show(dir, "HEAD", output = ShowOutput.NameStatus, paths = listOf("a")),
        GitCommands.show(dir, "HEAD", output = ShowOutput.Patch, firstParent = true),
        GitCommands.diffUntracked(dir, "a"),
        GitCommands.diffUntracked(dir, "a/b", output = DiffOutput.NumStat),
        GitCommands.untrackedFolderCheck(dir, "a/b"),
        GitCommands.branchList(dir, format = "%(refname)", scope = BranchScope.All),
        GitCommands.remoteList(dir),
        GitCommands.revParseToplevel(dir),
        GitCommands.add(dir, listOf("a")),
        GitCommands.unstage(dir, listOf("a")),
        GitCommands.discardWorktree(dir, listOf("a")),
        GitCommands.discardCheck(dir, listOf("a")),
        GitCommands.directoryProbe(dir, "a"),
        GitCommands.directoryPrefix(dir, "a"),
        GitCommands.commit(dir, "m"),
        GitCommands.createBranch(dir, "b", startPoint = "HEAD"),
        GitCommands.createBranch(dir, "b", startPoint = "refs/remotes/origin/b", track = BranchTracking.Track),
        GitCommands.createBranch(dir, "b", track = BranchTracking.NoTrack),
        GitCommands.switchBranch(dir, "b"),
        GitCommands.deleteBranch(dir, "b"),
        GitCommands.deleteBranch(dir, "b", allowUnmerged = true),
        GitCommands.fetch(dir),
        GitCommands.fetch(dir, "origin", prune = true),
        GitCommands.mergeFastForward(dir),
        GitCommands.mergeFastForward(dir, "refs/remotes/origin/main"),
        GitCommands.push(dir, "origin", "main"),
        GitCommands.push(dir, "origin", "main", setUpstream = true),
    )

    @Test
    fun `every builder makes env with the fixed variables, then git, and never clean or rm`() {
        val subcommands = everyBuilder(hostileDir).map { command ->
            val argv = command.argv
            assertEquals(ShellQuote.join(argv), command.commandLine)
            assertEquals("env", argv[0])
            val gitIndex = argv.indexOf("git")
            val variables = argv.subList(1, gitIndex)
            assertTrue(variables.all { Regex("[A-Z_]+=[A-Za-z0-9]+").matches(it) }, variables.toString())
            assertTrue("GIT_TERMINAL_PROMPT=0" in variables)
            // The directory probe enters a path below the directory; every other command the directory.
            assertTrue(argv[argv.indexOf("-C") + 1].startsWith(hostileDir))
            assertTrue(argv.subList(gitIndex + 1, argv.indexOf("-C")).all { it.startsWith("-") || "=" in it })
            argv[argv.indexOf("-C") + 2]
        }
        assertFalse("clean" in subcommands)
        assertFalse("rm" in subcommands)
        assertEquals(
            setOf(
                "status", "diff", "log", "show", "branch", "remote", "rev-parse", "add", "reset", "checkout-index",
                "commit", "switch", "fetch", "merge", "push", "version",
            ),
            subcommands.toSet(),
        )
    }

    @Test
    fun `every builder's command line reaches a real shell as env, then git`() {
        everyBuilder(hostileDir).forEach { command ->
            val delivered = deliver(command)
            assertTrue(delivered.args[delivered.args.indexOf("-C") + 1].startsWith(hostileDir))
            assertEquals("0", delivered.env["GIT_TERMINAL_PROMPT"])
        }
    }

    @Test
    fun `the commit message goes through stdin and never into the command line`() {
        val message = "Fix \$(touch pwned) `id` 'q'\n\nçğıöşü İ\n-rf\n"
        val command = GitCommands.commit("/srv/repo", message)
        assertFalse("pwned" in command.commandLine)
        assertFalse("çğıöşü" in command.commandLine)
        val delivered = deliver(command)
        assertEquals(listOf("commit", "-q", "--file=-"), delivered.gitArgs)
        assertContentEquals(message.toByteArray(Charsets.UTF_8), delivered.stdin)
        assertContentEquals(message.toByteArray(Charsets.UTF_8), command.toExecRequest().stdin)
    }

    @Test
    fun `branch changes`() {
        assertEquals(listOf("branch", "feature/çalış"), deliver(GitCommands.createBranch(hostileDir, "feature/çalış")).gitArgs)
        assertEquals(
            listOf("branch", "fix", "origin/main"),
            deliver(GitCommands.createBranch(hostileDir, "fix", startPoint = "origin/main")).gitArgs,
        )
        assertEquals(
            listOf("branch", "--track", "fix", "refs/remotes/origin/fix"),
            deliver(GitCommands.createBranch(hostileDir, "fix", startPoint = "refs/remotes/origin/fix", track = BranchTracking.Track)).gitArgs,
        )
        assertEquals(
            listOf("branch", "--no-track", "fix", "origin/main"),
            deliver(GitCommands.createBranch(hostileDir, "fix", startPoint = "origin/main", track = BranchTracking.NoTrack)).gitArgs,
        )
        assertEquals(
            listOf("branch", "fix", "origin/main"),
            deliver(GitCommands.createBranch(hostileDir, "fix", startPoint = "origin/main", track = BranchTracking.Default)).gitArgs,
        )
        assertEquals(listOf("switch", "-q", "--no-overwrite-ignore", "fix"), deliver(GitCommands.switchBranch(hostileDir, "fix")).gitArgs)
        assertEquals(listOf("branch", "-d", "fix"), deliver(GitCommands.deleteBranch(hostileDir, "fix")).gitArgs)
        assertEquals(
            listOf("branch", "-D", "fix"),
            deliver(GitCommands.deleteBranch(hostileDir, "fix", allowUnmerged = true)).gitArgs,
        )
    }

    @Test
    fun `remote operations`() {
        assertEquals(listOf("fetch"), deliver(GitCommands.fetch(hostileDir)).gitArgs)
        assertEquals(
            listOf("fetch", "--prune", "--multiple", "upstream"),
            deliver(GitCommands.fetch(hostileDir, "upstream", prune = true)).gitArgs,
        )
        assertEquals(
            listOf("merge", "-q", "--ff-only", "--no-squash", "--no-overwrite-ignore", "--no-autostash", "@{upstream}"),
            deliver(GitCommands.mergeFastForward(hostileDir)).gitArgs,
        )
        assertEquals(
            listOf("merge", "-q", "--ff-only", "--no-squash", "--no-overwrite-ignore", "--no-autostash", "refs/remotes/origin/main"),
            deliver(GitCommands.mergeFastForward(hostileDir, "refs/remotes/origin/main")).gitArgs,
        )
        assertEquals(
            listOf("push", "--no-follow-tags", "--no-recurse-submodules", "origin", "refs/heads/main:refs/heads/main"),
            deliver(GitCommands.push(hostileDir, "origin", "main")).gitArgs,
        )
        assertEquals(
            listOf("push", "--no-follow-tags", "--no-recurse-submodules", "--set-upstream", "origin", "refs/heads/ç/x:refs/heads/ç/x"),
            deliver(GitCommands.push(hostileDir, "origin", "ç/x", setUpstream = true)).gitArgs,
        )
    }

    @Test
    fun `remote operations get a long timeout and local ones the default`() {
        assertEquals(10.minutes, GitCommands.fetch(hostileDir).timeout)
        assertEquals(ExecRequest.DEFAULT_TIMEOUT, GitCommands.mergeFastForward(hostileDir).timeout)
        assertEquals(10.minutes, GitCommands.push(hostileDir, "origin", "main").timeout)
        assertEquals(ExecRequest.DEFAULT_TIMEOUT, GitCommands.status(hostileDir).timeout)
        assertEquals(2.minutes, GitCommands.status(hostileDir, timeout = 2.minutes).timeout)
        assertNull(GitCommands.fetch(hostileDir, timeout = null).toExecRequest().timeout)
    }

    @Test
    fun `toString hides the directory, the paths and the message`() {
        val commands = listOf(
            GitCommands.add(hostileDir, listOf("secret-path.txt")),
            GitCommands.commit(hostileDir, "secret message"),
            GitCommands.createBranch(hostileDir, "secret-branch"),
        )
        commands.forEach { command ->
            val text = command.toString()
            assertNotNull(text)
            assertFalse("secret" in text, text)
            assertFalse("srv" in text, text)
        }
    }
}
