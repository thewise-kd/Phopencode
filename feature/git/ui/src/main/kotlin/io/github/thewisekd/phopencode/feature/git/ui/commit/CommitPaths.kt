package io.github.thewisekd.phopencode.feature.git.ui.commit

private const val SEPARATOR = '/'

/**
 * The last part of a repository path, the row's headline. An untracked folder, which git prints
 * with a trailing slash, keeps the slash so that it reads as a folder. As in the status screen.
 */
internal fun fileName(path: String): String {
    val trimmed = path.trimEnd(SEPARATOR)
    val name = trimmed.substringAfterLast(SEPARATOR)
    return if (trimmed.length < path.length) "$name$SEPARATOR" else name
}

/** The folder of a repository path, or null for a path at the repository's root. */
internal fun folderOf(path: String): String? {
    val trimmed = path.trimEnd(SEPARATOR)
    val cut = trimmed.lastIndexOf(SEPARATOR)
    return if (cut > 0) trimmed.substring(0, cut) else null
}
