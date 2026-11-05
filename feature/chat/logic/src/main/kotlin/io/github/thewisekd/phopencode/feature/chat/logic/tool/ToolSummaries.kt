package io.github.thewisekd.phopencode.feature.chat.logic.tool

import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolSummary

/**
 * Turns a tool's name and input into its one-line [ToolSummary].
 *
 * Names and fields follow the V2 server (`docs/api/README.md`, the recorded flows), with the V1
 * names accepted as well:
 * - `shell` (V1 `bash`): `command`.
 * - `edit`, `multiedit`, `write`: `path` (V1 `filePath`). Line counts from `metadata.files[]`
 *   (`additions`, `deletions`, summed over the files), or V1 `metadata.filediff`.
 * - `read`: `path` (V1 `filePath`).
 * - `grep`, `glob`: `pattern`, and `path` when given.
 *
 * Any other tool, or a known one whose main field is missing, blank or not text (as while its
 * input still streams as raw text), gets [ToolSummary.Generic].
 */
internal object ToolSummaries {
    fun of(name: String, input: DataValue?, metadata: Map<String, DataValue>): ToolSummary {
        val fields = (input as? DataValue.Fields)?.values.orEmpty()
        return when (name) {
            "shell", "bash" -> fields.text("command")?.let { ToolSummary.Shell(it) }
            "edit", "multiedit", "write" -> path(fields)?.let { path ->
                val (additions, deletions) = lineCounts(metadata)
                ToolSummary.Edit(path, additions = additions, deletions = deletions)
            }
            "read" -> path(fields)?.let { ToolSummary.Read(it) }
            "grep", "glob" -> fields.text("pattern")?.let { ToolSummary.Search(it, path = fields.text("path")) }
            else -> null
        } ?: ToolSummary.Generic(name)
    }

    private fun path(fields: Map<String, DataValue>): String? = fields.text("path") ?: fields.text("filePath")

    private fun lineCounts(metadata: Map<String, DataValue>): Pair<Int?, Int?> {
        val files = (metadata["files"] as? DataValue.Items)?.values
            ?.mapNotNull { (it as? DataValue.Fields)?.values }
        if (!files.isNullOrEmpty()) {
            return files.sumOf("additions") to files.sumOf("deletions")
        }
        val diff = (metadata["filediff"] as? DataValue.Fields)?.values ?: return null to null
        return diff.count("additions") to diff.count("deletions")
    }

    /** Null unless every file reports a valid count. */
    private fun List<Map<String, DataValue>>.sumOf(field: String): Int? {
        var total = 0L
        for (file in this) {
            total += file.count(field) ?: return null
        }
        return total.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun Map<String, DataValue>.text(field: String): String? =
        (this[field] as? DataValue.Text)?.value?.takeIf { it.isNotBlank() }

    /** A whole, non-negative number that fits an [Int]; null otherwise. */
    private fun Map<String, DataValue>.count(field: String): Int? {
        val number = (this[field] as? DataValue.Num)?.value ?: return null
        return runCatching { number.intValueExact() }.getOrNull()?.takeIf { it >= 0 }
    }
}
