package io.github.thewisekd.phopencode.core.git.parse

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A real git repository in a temporary directory, driven by the local `git` with a clean
 * configuration and fixed identities and dates, so its output is real and repeatable.
 */
class TestRepo private constructor(val dir: File) {
    private var tick = 0

    /** Runs git here and returns stdout; fails on a non-zero exit unless [allowFailure]. */
    fun git(vararg args: String, allowFailure: Boolean = false): ByteArray =
        run(listOf("git") + args, allowFailure)

    /** Runs a `sh -c` script here, for file names Java cannot create (non-UTF-8 bytes). */
    fun sh(script: String): ByteArray = run(listOf("sh", "-c", script), allowFailure = false)

    fun write(path: String, content: String): File = writeBytes(path, content.toByteArray(Charsets.UTF_8))

    fun writeBytes(path: String, content: ByteArray): File {
        val file = File(dir, path)
        file.parentFile.mkdirs()
        file.writeBytes(content)
        return file
    }

    /** Stages everything and commits with [message]; each commit gets a later fixed date. */
    fun commitAll(message: String) {
        git("add", "-A")
        git("commit", "-q", "--allow-empty", "-m", message)
    }

    fun head(): String = git("rev-parse", "HEAD").toString(Charsets.UTF_8).trim()

    private fun run(command: List<String>, allowFailure: Boolean): ByteArray {
        tick++
        val date = "${BASE_TIME + tick * 60} +0000"
        val pb = ProcessBuilder(command).directory(dir).redirectErrorStream(false)
        val env = pb.environment()
        env.keys.removeIf { it.startsWith("GIT_") }
        env["GIT_CONFIG_GLOBAL"] = "/dev/null"
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        env["GIT_AUTHOR_NAME"] = "Ayşe Yılmaz"
        env["GIT_AUTHOR_EMAIL"] = "ayse@example.invalid"
        env["GIT_COMMITTER_NAME"] = "Committer"
        env["GIT_COMMITTER_EMAIL"] = "committer@example.invalid"
        env["GIT_AUTHOR_DATE"] = date
        env["GIT_COMMITTER_DATE"] = date
        env["GIT_TERMINAL_PROMPT"] = "0"
        env["LC_ALL"] = "C.UTF-8"
        // Output goes to files and stdin comes from /dev/null, so nothing can block before the timed
        // wait: a hanging git is killed after the timeout instead of hanging the test run.
        val outFile = File.createTempFile("git-out", ".bin")
        val errFile = File.createTempFile("git-err", ".txt")
        try {
            pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            pb.redirectOutput(outFile)
            pb.redirectError(errFile)
            val process = pb.start()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                error("timed out after $TIMEOUT_SECONDS s: $command")
            }
            if (!allowFailure) {
                check(process.exitValue() == 0) { "failed (${process.exitValue()}): $command\n${errFile.readText()}" }
            }
            return outFile.readBytes()
        } finally {
            outFile.delete()
            errFile.delete()
        }
    }

    companion object {
        private const val BASE_TIME = 1_700_000_000L
        private const val TIMEOUT_SECONDS = 60L

        /** A fresh repository with branch `main`. */
        fun init(root: File, name: String = "repo"): TestRepo {
            val dir = File(root, name).apply { mkdirs() }
            return TestRepo(dir).apply {
                git("init", "-q", "-b", "main")
                git("config", "core.autocrlf", "false")
                git("config", "core.fileMode", "true")
            }
        }

        /** A bare repository to push to and clone from. */
        fun bare(root: File, name: String): TestRepo {
            val dir = File(root, name).apply { mkdirs() }
            return TestRepo(dir).apply { git("init", "-q", "--bare", "-b", "main") }
        }

        fun clone(root: File, from: TestRepo, name: String): TestRepo {
            TestRepo(root).git("clone", "-q", from.dir.absolutePath, name)
            return TestRepo(File(root, name)).apply { git("config", "core.autocrlf", "false") }
        }
    }
}

fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

/** UTF-8 bytes with the given raw bytes appended, for non-UTF-8 names. */
fun bytes(text: String, vararg raw: Int): ByteArray = bytes(text) + ByteArray(raw.size) { raw[it].toByte() }
