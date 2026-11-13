package io.github.thewisekd.phopencode.e2e.preview

/**
 * The test tags the preview's end-to-end tests look for. Each is `internal` to the module that sets
 * it, so its value is repeated here; the comment on each names the constant it copies.
 */
internal object PreviewTags {
    /** feature/sessions/ui/…/list/SessionListTags.kt: `SESSION_PREVIEW_ITEM_TAG`. */
    const val SESSIONS_MENU_PREVIEW = "sessions_list:preview"

    /** feature/preview/ui/…/ports/PortsTags.kt: `PortsTags.port(port)`. */
    fun port(port: Int): String = "preview_ports_port:$port"

    /** feature/preview/ui/…/page/PageTags.kt: `PageTags.RELOAD`. */
    const val PAGE_RELOAD = "preview_page_reload"

    /** feature/preview/ui/…/page/PageTags.kt: `PageTags.WEB_VIEW`. */
    const val PAGE_WEB_VIEW = "preview_page_web_view"

    /** feature/preview/ui/…/page/PageTags.kt: `PageTags.CONNECTION_LOST`. */
    const val PAGE_CONNECTION_LOST = "preview_page_connection_lost"

    /** feature/preview/ui/…/page/PageTags.kt: `PageTags.EXTERNAL_DIALOG`. */
    const val PAGE_EXTERNAL_DIALOG = "preview_page_external_dialog"

    /** feature/preview/ui/…/page/console/ConsoleTags.kt: `ConsoleTags.BUTTON`. */
    const val CONSOLE_BUTTON = "preview_console_button"

    /** feature/preview/ui/…/page/console/ConsoleTags.kt: `ConsoleTags.PANEL`. */
    const val CONSOLE_PANEL = "preview_console_panel"

    /** feature/preview/ui/…/page/console/ConsoleTags.kt: `ConsoleTags.HIDE`. */
    const val CONSOLE_HIDE = "preview_console_hide"

    /**
     * feature/preview/ui/…/page/console/ConsoleTags.kt: `ConsoleTags.filter(level)`, with the name of
     * a `ConsoleLevel` such as `Error`, or null for the chip that shows every level.
     */
    fun consoleFilter(level: String?): String = "preview_console_filter_${level ?: "all"}"

    /** feature/preview/ui/…/page/viewport/ViewportTags.kt: `ViewportTags.TOGGLE`. */
    const val VIEWPORT_TOGGLE = "preview_viewport_toggle"
}
