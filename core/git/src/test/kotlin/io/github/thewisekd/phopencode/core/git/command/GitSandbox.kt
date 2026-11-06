package io.github.thewisekd.phopencode.core.git.command

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A private world for real git: its own `HOME` with a minimal `.gitconfig`, no system config, and
 * a scratch folder that commands run in. Setup and checks call git directly with an argument
 * vector, never through a shell, so they cannot hide a quoting bug in the code under test.
 */
internal class GitSandbox(val root: Path) {
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
    val runner = GitRunner(exec)

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
            """.trimIndent() + "\n",
        )
    }

    /** Creates a repository under [root] in a folder called [name]. */
    fun newRepo(name: String = "repo"): Path {
        val repo = root.resolve(name).createDirectories()
        git(repo, "init", "-q")
        return repo
    }

    /** Runs git directly in [dir] and returns stdout; fails the test on a non-zero exit. */
    fun git(dir: Path, vararg args: String, stdin: ByteArray? = null): ByteArray {
        val result = gitResult(dir, *args, stdin = stdin)
        check(result.exitCode == 0) { "git ${args.firstOrNull()} failed: ${String(result.stderr)}" }
        return result.stdout
    }

    class Direct(val stdout: ByteArray, val stderr: ByteArray, val exitCode: Int)

    fun gitResult(dir: Path, vararg args: String, stdin: ByteArray? = null): Direct {
        val builder = ProcessBuilder(listOf("git", *args)).directory(dir.toFile())
        builder.environment().apply {
            clear()
            putAll(environment)
        }
        val process = builder.start()
        process.outputStream.use { stream -> stdin?.let(stream::write) }
        val stderrFile = Files.createTempFile(root, "err", ".txt")
        val errThread = Thread { Files.write(stderrFile, process.errorStream.readAllBytes()) }.apply { start() }
        val stdout = process.inputStream.readAllBytes()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "git did not finish" }
        errThread.join()
        val stderr = Files.readAllBytes(stderrFile)
        Files.delete(stderrFile)
        return Direct(stdout, stderr, process.exitValue())
    }

    fun gitText(dir: Path, vararg args: String): String = String(git(dir, *args), Charsets.UTF_8)

    /** Writes [content] to [name] in [repo], and commits it with git directly. */
    fun commitFile(repo: Path, name: String, content: String, message: String = "add") {
        repo.resolve(name).writeText(content)
        git(repo, "add", "--", name)
        git(repo, "commit", "-q", "-m", message)
    }

    /** Every file anywhere in the sandbox whose name starts with `pwned`. */
    fun pwnedFiles(): List<Path> = Files.walk(root).use { paths ->
        paths.filter { it.fileName.toString().startsWith("pwned") }.toList()
    }
}
