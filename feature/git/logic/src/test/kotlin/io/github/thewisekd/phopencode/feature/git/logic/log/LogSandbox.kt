package io.github.thewisekd.phopencode.feature.git.logic.log

import io.github.thewisekd.phopencode.feature.git.logic.contract.ExecGitConnection
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * A private world for real git: its own `HOME` with a minimal `.gitconfig`, no system config, and
 * a scratch folder the local shell runs in. Setup calls git directly with an argument vector, never
 * through a shell, so it cannot hide a quoting bug in the code under test.
 */
internal class LogSandbox(val root: Path) {
    private val home: Path = root.resolve("home").createDirectories()

    /** Where the local shell runs; a `$(touch pwned)` that ran would land here. */
    private val cwd: Path = root.resolve("cwd").createDirectories()

    private val environment: Map<String, String> = mapOf(
        "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
        "HOME" to home.absolutePathString(),
        "XDG_CONFIG_HOME" to home.resolve(".config").absolutePathString(),
        "GIT_CONFIG_NOSYSTEM" to "1",
    )

    val exec = LocalShellExec(cwd, environment)
    val git = ExecGitConnection(exec)

    /** Each commit gets the next second, so that the history's order is fixed. */
    private var clock = 1_700_000_000L

    init {
        home.resolve(".gitconfig").writeText(
            "[user]\n\tname = Ayşe Yılmaz\n\temail = ayse@example.com\n[init]\n\tdefaultBranch = main\n" +
                "[advice]\n\tdetachedHead = false\n",
        )
    }

    fun newRepo(name: String = "repo"): Path {
        val repo = root.resolve(name).createDirectories()
        git(repo, "init", "-q")
        return repo
    }

    fun git(dir: Path, vararg args: String): String {
        val time = "@${clock++} +0300"
        val builder = ProcessBuilder(listOf("git", *args)).directory(dir.toFile())
        builder.environment().apply {
            clear()
            putAll(environment)
            put("GIT_AUTHOR_DATE", time)
            put("GIT_COMMITTER_DATE", time)
        }
        builder.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
        val errors = Files.createTempFile(root, "err", ".txt")
        builder.redirectError(errors.toFile())
        val process = builder.start()
        val stdout = process.inputStream.readAllBytes()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "git did not finish" }
        val stderr = Files.readString(errors)
        Files.delete(errors)
        check(process.exitValue() == 0) { "git ${args.firstOrNull()} failed: $stderr" }
        return String(stdout, Charsets.UTF_8)
    }

    /** Writes [content] to [name] and commits it; gives the new commit's hash. */
    fun commitFile(repo: Path, name: String, content: String, message: String = "değişiklik"): String {
        repo.resolve(name).also { it.parent.createDirectories() }.writeText(content)
        git(repo, "add", "--", name)
        return commit(repo, message)
    }

    fun commitBytes(repo: Path, name: String, content: ByteArray, message: String): String {
        repo.resolve(name).writeBytes(content)
        git(repo, "add", "--", name)
        return commit(repo, message)
    }

    fun commit(repo: Path, message: String, allowEmpty: Boolean = false): String {
        val args = buildList {
            addAll(listOf("commit", "-q"))
            if (allowEmpty) add("--allow-empty")
            addAll(listOf("-m", message))
        }
        git(repo, *args.toTypedArray())
        return head(repo)
    }

    fun head(repo: Path): String = git(repo, "rev-parse", "HEAD").trim()

    /** Hashes newest first, as `log` shows them. */
    fun revList(repo: Path, revision: String = "HEAD"): List<String> = git(repo, "rev-list", revision, "--").lines().filter { it.isNotEmpty() }

    /** Every file anywhere in the sandbox whose name starts with `pwned`. */
    fun pwnedFiles(): List<Path> = Files.walk(root).use { paths -> paths.filter { it.fileName.toString().startsWith("pwned") }.toList() }
}
