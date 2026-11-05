package io.github.thewisekd.phopencode.feature.files.ui.tree

/** One folder of the path bar: the name it shows and the folder a tap opens. */
internal data class PathSegment(val name: String, val path: String) {
    override fun toString(): String = "PathSegment(***)"
}

/**
 * The path bar's folders, from the project's [directory] down to [folder], the one shown. The
 * project's folder is one segment named after its last part: the folders above it are not the
 * tree's to open. A [folder] not below [directory], which the tree logic never shows, is one
 * segment of its own after it.
 */
internal fun pathSegments(directory: String, folder: String): List<PathSegment> {
    val root = directory.trimEnd('/').ifEmpty { "/" }
    val shown = folder.trimEnd('/').ifEmpty { "/" }
    val first = PathSegment(name = if (root == "/") root else root.substringAfterLast('/'), path = root)
    if (shown == root) return listOf(first)
    val prefix = if (root == "/") root else "$root/"
    if (!shown.startsWith(prefix)) return listOf(first, PathSegment(shown, shown))
    var path = root
    val below = shown.removePrefix(prefix).split('/').filter { it.isNotEmpty() }.map { name ->
        path = if (path == "/") "/$name" else "$path/$name"
        PathSegment(name, path)
    }
    return listOf(first) + below
}
