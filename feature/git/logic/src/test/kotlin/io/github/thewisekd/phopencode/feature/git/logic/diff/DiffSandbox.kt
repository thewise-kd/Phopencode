package io.github.thewisekd.phopencode.feature.git.logic.diff

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A private world for real git: its own `HOME` with a minimal `.gitconfig`, no system config, and
 * a scratch folder the local shell runs in. Setup calls git directly with an argument vector, never
 * through a shell, so it cannot hide a quoting bug in the code under test.
 */
internal class DiffSandbox(val root: Path) {
    private val home: Path = root.resolve("home").createDirectories()

    /** Where the local shell runs; a `$(touch pwned)` that ran would land here. */
    private val cwd: Path = root.resolve("cwd").createDirectories()

    private val environment: Map<String, String> = mapOf(
        "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
        "HOME" to home.absolutePathString(),
        "XDG_CONFIG_HOME" to home.resolve(".config").absolutePathString(),
        "GIT_CONFIG_NOSYSTEM" to "1",
        "LC_ALL" to "C.UTF-8",
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
            """.trimIndent() + "\n",
        )
    }

    fun newRepo(name: String = "repo"): Path {
        val repo = root.resolve(name).createDirectories()
        git(repo, "init", "-q")
        return repo
    }

    /** Runs git in [dir] and returns stdout; fails the test on a non-zero exit. */
    fun git(dir: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args)).directory(dir.toFile()).redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(this@DiffSandbox.environment)
            }
            .start()
        process.outputStream.close()
        val output = process.inputStream.readAllBytes()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "git did not finish" }
        check(process.exitValue() == 0) { "git ${args.firstOrNull()} failed: ${String(output)}" }
        return String(output, Charsets.UTF_8)
    }

    /** Runs a fixed shell [script] in [dir], for what Java cannot do, such as non-UTF-8 names. */
    fun shell(dir: Path, script: String) {
        val process = ProcessBuilder("/bin/sh", "-c", script).directory(dir.toFile()).redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(this@DiffSandbox.environment)
            }
            .start()
        process.outputStream.close()
        val output = process.inputStream.readAllBytes()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "shell did not finish" }
        check(process.exitValue() == 0) { "shell failed: ${String(output)}" }
    }

    fun write(repo: Path, name: String, content: String) {
        val file = repo.resolve(name)
        file.parent.createDirectories()
        file.writeText(content)
    }

    fun commitAll(repo: Path, message: String = "change"): String {
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", message)
        return git(repo, "rev-parse", "HEAD").trim()
    }

    /** Every file anywhere in the sandbox whose name starts with `pwned`. */
    fun pwnedFiles(): List<Path> = Files.walk(root).use { paths ->
        paths.filter { it.fileName.toString().startsWith("pwned") }.toList()
    }
}
