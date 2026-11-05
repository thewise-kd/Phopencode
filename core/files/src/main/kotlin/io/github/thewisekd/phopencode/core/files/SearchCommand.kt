package io.github.thewisekd.phopencode.core.files

import io.github.thewisekd.phopencode.core.ssh.quote.ShellQuote

/**
 * The shell command of a content search, and how to read what it reports.
 *
 * One command line picks the tool on the server: `rg` if `command -v` finds it, else `grep`, else
 * neither. Without `head` nothing runs. The tool's and `head`'s own error output go to `/dev/null`,
 * so stderr holds only the markers this command prints: `<tool> <exit status>` and
 * `head <exit status>` (each stage's own status, in POSIX sh without `pipefail`), or `none`. The
 * output passes through `head -c`, which cuts it at the byte limit; the tool then ends by itself on
 * the closed pipe, so nothing here has to stop a process.
 *
 * Every variable part, the search text and the folder, is one [ShellQuote]d word after `-e` or after
 * `--`, so neither a leading `-` nor any shell syntax in them has an effect.
 */
internal object SearchCommand {
    const val RG = "rg"
    const val GREP = "grep"
    const val HEAD = "head"
    const val NONE = "none"

    fun build(directory: String, query: ContentQuery): String {
        val rg = ShellQuote.join(ripgrep(directory, query))
        val grep = ShellQuote.join(grep(directory, query))
        val head = ShellQuote.join(listOf("head", "-c", query.maxOutputBytes.toString()))
        // The status after a pipeline is its last stage's: head's.
        val capped = { tool: String, name: String ->
            "{ $tool 2>/dev/null; echo \"$name \$?\" >&2; } | $head 2>/dev/null; echo \"$HEAD \$?\" >&2"
        }
        return "if ! command -v head >/dev/null 2>&1; then echo $NONE >&2; " +
            "elif command -v rg >/dev/null 2>&1; then ${capped(rg, RG)}; " +
            "elif command -v grep >/dev/null 2>&1; then ${capped(grep, GREP)}; " +
            "else echo $NONE >&2; fi"
    }

    private fun ripgrep(directory: String, query: ContentQuery): List<String> = buildList {
        add("rg")
        add("--no-config")
        add("--color=never")
        add("--no-heading")
        add("--with-filename")
        add("--line-number")
        add("--null")
        add("--max-count=${query.maxMatchesPerFile}")
        if (!query.regex) add("--fixed-strings")
        if (query.ignoreCase) add("--ignore-case")
        add("-e")
        add(query.text)
        add("--")
        add(directory)
    }

    private fun grep(directory: String, query: ContentQuery): List<String> = buildList {
        add("grep")
        add("-r")
        add("-n")
        add("-H")
        add("-I")
        add("--null")
        add("--color=never")
        add("--exclude-dir=.git")
        add("-m")
        add(query.maxMatchesPerFile.toString())
        add(if (query.regex) "-E" else "-F")
        if (query.ignoreCase) add("-i")
        add("-e")
        add(query.text)
        add("--")
        add(directory)
    }

    /** What the marker on stderr says. */
    sealed interface Outcome {
        data object Unavailable : Outcome

        /**
         * @property status The tool's exit status.
         * @property headStatus `head`'s exit status.
         */
        data class Ran(val tool: SearchTool, val status: Int, val headStatus: Int) : Outcome

        data object Unknown : Outcome
    }

    /** Reads the markers; a missing or garbled one is [Outcome.Unknown]. */
    fun outcome(stderr: ByteArray): Outcome {
        val lines = stderr.decodeToString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (NONE in lines) return Outcome.Unavailable
        var tool: SearchTool? = null
        var status: Int? = null
        var headStatus: Int? = null
        lines.forEach { line ->
            val parts = line.split(' ')
            val value = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || value == null) return Outcome.Unknown
            when (parts[0]) {
                RG -> tool = SearchTool.Ripgrep
                GREP -> tool = SearchTool.Grep
                HEAD -> {
                    headStatus = value
                    return@forEach
                }
                else -> return Outcome.Unknown
            }
            status = value
        }
        return Outcome.Ran(tool ?: return Outcome.Unknown, status ?: return Outcome.Unknown, headStatus ?: return Outcome.Unknown)
    }
}
