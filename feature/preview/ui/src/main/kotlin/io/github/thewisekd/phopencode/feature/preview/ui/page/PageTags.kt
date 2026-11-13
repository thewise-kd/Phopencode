package io.github.thewisekd.phopencode.feature.preview.ui.page

/** Test tags of the preview page; its root keeps the `placeholder:<route>` tag instead. */
internal object PageTags {
    const val RELOAD = "preview_page_reload"
    const val CONTENT = "preview_page_content"
    const val WEB_VIEW = "preview_page_web_view"
    const val OPENING = "preview_page_opening"
    const val PROGRESS = "preview_page_progress"
    const val PULL = "preview_page_pull"
    const val LOAD_ERROR = "preview_page_load_error"
    const val NOTHING_LISTENS = "preview_page_nothing_listens"
    const val CONNECTION_LOST = "preview_page_connection_lost"
    const val EXTERNAL_DIALOG = "preview_page_external_dialog"
    const val EXTERNAL_FAILED = "preview_page_external_failed"
}
