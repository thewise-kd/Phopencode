package io.github.thewisekd.phopencode.feature.files.logic.tree

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import io.github.thewisekd.phopencode.feature.files.logic.contract.GitBadge
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeEntry
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeSort
import kotlin.time.Instant

/**
 * The git badges of one repository's changes, by path relative to the project's folder.
 *
 * A change path is read relative to the project's folder (as `VcsApi.status` gives it for that
 * folder), or absolute inside it; a trailing slash, as git gives an untracked folder, is dropped.
 * Paths outside the project's folder and statuses the app does not know give no badge.
 */
internal class GitBadges(changes: List<VcsFile>, private val paths: TreePaths) {
    private val own = HashMap<String, GitBadge>()
    private val holding = HashSet<String>()

    init {
        for (change in changes) {
            val path = relative(change.path) ?: continue
            if (path.isEmpty()) continue
            badge(change.status)?.let { own[path] = it }
            var cut = path.lastIndexOf('/')
            while (cut > 0) {
                val ancestor = path.substring(0, cut)
                if (!holding.add(ancestor)) break
                cut = ancestor.lastIndexOf('/')
            }
        }
    }

    /** A file's own status; for a folder, its own status or [GitBadge.ContainsChanges]. */
    fun of(entry: TreeEntry): GitBadge? {
        val path = relative(entry.entry.path) ?: return null
        if (path.isEmpty()) return null
        own[path]?.let { return it }
        return if (entry.isFolder && path in holding) GitBadge.ContainsChanges else null
    }

    private fun relative(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("/")) {
            paths.relative(trimmed)
        } else {
            TreePaths.normalize("/$trimmed")?.removePrefix("/")
        }
    }

    private fun badge(status: VcsFileStatus): GitBadge? = when (status) {
        VcsFileStatus.Added -> GitBadge.Added
        VcsFileStatus.Modified -> GitBadge.Modified
        VcsFileStatus.Deleted -> GitBadge.Deleted
        VcsFileStatus.Renamed -> GitBadge.Renamed
        VcsFileStatus.Untracked -> GitBadge.Untracked
        is VcsFileStatus.Unknown -> null
    }
}

/** The rows of a listing, badged; all of them, hidden or not, in no order. */
internal fun badged(entries: List<FileEntry>, badges: GitBadges): List<TreeEntry> =
    entries.map { entry -> TreeEntry(entry).let { row -> row.copy(badge = badges.of(row)) } }

/** What the tree shows of [rows]: folders first, then [sort]; dot names only with [showHidden]. */
internal fun shown(rows: List<TreeEntry>, sort: TreeSort, showHidden: Boolean): List<TreeEntry> =
    rows.filter { showHidden || !it.isHidden }.sortedWith(order(sort))

private val BY_NAME: Comparator<TreeEntry> =
    compareBy<TreeEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.entry.name }
        .thenBy { it.entry.name }
        .thenBy { it.entry.path }

private fun order(sort: TreeSort): Comparator<TreeEntry> {
    val folders = compareBy<TreeEntry> { !it.isFolder }
    val within: Comparator<TreeEntry> = when (sort) {
        TreeSort.Name -> BY_NAME
        // Newest first; unknown times last.
        TreeSort.Modified -> compareBy<TreeEntry, Instant?>(nullsLast(reverseOrder())) { it.entry.modifiedAt }.then(BY_NAME)
        // Largest first; unknown sizes last.
        TreeSort.Size -> compareBy<TreeEntry, Long?>(nullsLast(reverseOrder())) { it.entry.sizeBytes }.then(BY_NAME)
    }
    return folders.then(within)
}
