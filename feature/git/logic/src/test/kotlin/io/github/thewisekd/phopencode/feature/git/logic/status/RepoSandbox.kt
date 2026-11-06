package io.github.thewisekd.phopencode.feature.git.logic.status

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A private world for real git: its own `HOME` with a minimal `.gitconfig`, no system config, and a
 * scratch folder the local shell runs in. Setup and checks call git directly with an argument
 * vector, never through a shell, so they cannot hide a quoting bug in the code under test.
 */
internal class RepoSandbox(val root: Path) {
    val home: Path = root.resolve("home").createDirectories()

    /** The working directory of the local shell; a `$(touch pwned)` that ran would land here. */
    val cwd: Path = root.resolve("cwd").createDirectories()

    val environment: Map<String, String> = mapOf(
        "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
        "HOME" to home.absolutePathString(),
        "XDG_CONFIG_HOME" to home.resolve(".config").absolutePathString(),
        "GIT_CONFIG_NOSYSTEM" to "1",
    )

    val exec = LocalShellExec(cwd, environment)

    init {
        home.resolve(".gitconfig").writeText(
            """
            [user]
            	name = Test User
            	email = test@example.com
            [init]
            	defaultBranch = main
            [advice]
            	detachedHead = false
            [merge]
            	conflictStyle = merge
            """.trimIndent() + "\n",
        )
    }

    /** A new repository in a folder called [name] under [root]. */
    fun newRepo(name: String = "repo"): Path {
        val repo = root.resolve(name).createDirectories()
        git(repo, "init", "-q")
        return repo
    }

    /** Runs git directly in [dir] and returns stdout; fails the test on a non-zero exit. */
    fun git(dir: Path, vararg args: String): ByteArray {
        val result = run(dir, listOf("git", *args))
        check(result.exitCode == 0) { "git ${args.firstOrNull()} failed: ${String(result.stderr)}" }
        return result.stdout
    }

    /** Runs git directly and returns its exit code, for commands expected to fail (a conflict). */
    fun gitExit(dir: Path, vararg args: String): Int = run(dir, listOf("git", *args)).exitCode

    fun gitText(dir: Path, vararg args: String): String = String(git(dir, *args), Charsets.UTF_8)

    /** Writes [content] to [name] in [repo] and commits it with git directly. */
    fun commitFile(repo: Path, name: String, content: String, message: String = "change $name") {
        val file = repo.resolve(name)
        file.parent.createDirectories()
        file.writeText(content)
        git(repo, "add", "--", name)
        git(repo, "commit", "-q", "-m", message)
    }

    /** NUL-separated output of a git listing command, as raw byte strings. */
    fun gitList(dir: Path, vararg args: String): List<ByteArray> {
        val out = git(dir, *args)
        val items = mutableListOf<ByteArray>()
        var start = 0
        for (i in out.indices) {
            if (out[i] == 0.toByte()) {
                if (i > start) items += out.copyOfRange(start, i)
                start = i + 1
            }
        }
        return items
    }

    /** Paths in the index that differ from `HEAD` (or every indexed path before the first commit). */
    fun stagedPaths(repo: Path): Set<String> {
        val hasHead = gitExit(repo, "rev-parse", "-q", "--verify", "HEAD") == 0
        val list = if (hasHead) gitList(repo, "diff", "--cached", "--name-only", "-z") else gitList(repo, "ls-files", "-z")
        return list.map { String(it, Charsets.UTF_8) }.toSet()
    }

    /** Untracked paths, as git itself lists them. */
    fun untrackedPaths(repo: Path): Set<String> =
        gitList(repo, "ls-files", "-z", "--others", "--exclude-standard").map { String(it, Charsets.UTF_8) }.toSet()

    /**
     * Runs a program directly (no shell) in [dir], stdin closed, both outputs read on their own
     * threads. Past [timeoutSeconds] the child is ended (see [endOwnChild]) and the test fails.
     */
    fun run(dir: Path, argv: List<String>, timeoutSeconds: Long = 30): Direct {
        val builder = ProcessBuilder(argv).directory(dir.toFile())
        builder.environment().apply {
            clear()
            putAll(environment)
        }
        val process = builder.start()
        process.outputStream.close()
        val stdout = AtomicReference(ByteArray(0))
        val stderr = AtomicReference(ByteArray(0))
        val readers = listOf(
            thread(isDaemon = true, name = "sandbox-stdout") { runCatching { stdout.set(process.inputStream.readAllBytes()) } },
            thread(isDaemon = true, name = "sandbox-stderr") { runCatching { stderr.set(process.errorStream.readAllBytes()) } },
        )
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            endOwnChild(process, readers)
            error("${argv.first()} did not finish")
        }
        readers.forEach { it.join(5_000) }
        return Direct(stdout.get(), stderr.get(), process.exitValue())
    }

    class Direct(val stdout: ByteArray, val stderr: ByteArray, val exitCode: Int)

    /** Every file anywhere in the sandbox whose name starts with `pwned`. */
    fun pwnedFiles(): List<Path> = Files.walk(root).use { paths ->
        paths.filter { it.fileName.toString().startsWith("pwned") }.toList()
    }
}
