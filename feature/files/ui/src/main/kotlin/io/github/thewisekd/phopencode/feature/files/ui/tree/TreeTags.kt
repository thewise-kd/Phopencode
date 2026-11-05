package io.github.thewisekd.phopencode.feature.files.ui.tree

/** Test tags on the tree screen. None of them carries a path or a file name. */
internal object TreeTags {
    /** The path bar: the Up button and one button per folder from the project's folder down. */
    const val PATH_BAR: String = "tree:path_bar"

    /** A folder of the path bar; the last one is the folder shown. */
    const val PATH_SEGMENT: String = "tree:path_segment"

    /** The list of the folder's entries. */
    const val LIST: String = "tree:list"

    /** One entry of the list. */
    const val ROW: String = "tree:row"

    /** A row's git badge. */
    const val BADGE: String = "tree:badge"

    /** A row's `.gitignore` marker. */
    const val IGNORED: String = "tree:ignored"

    /** The still text of a first load, and the one of a reload above the rows. */
    const val LOADING: String = "tree:loading"
    const val REFRESHING: String = "tree:refreshing"

    /** The folder options dialog and its controls. */
    const val OPTIONS: String = "tree:options"
    const val NEW_FOLDER: String = "tree:new_folder"
    const val REFRESH: String = "tree:refresh"
    const val SHOW_HIDDEN: String = "tree:show_hidden"
    const val SORT_NAME: String = "tree:sort_name"
    const val SORT_MODIFIED: String = "tree:sort_modified"
    const val SORT_SIZE: String = "tree:sort_size"
    const val OPTIONS_CLOSE: String = "tree:options_close"

    /** The error banner. */
    const val ERROR: String = "tree:error"
}
