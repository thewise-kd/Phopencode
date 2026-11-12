package io.github.thewisekd.phopencode.feature.preview.ui.ports

/** Test tags of the port picker; its root keeps the `placeholder:<route>` tag instead. */
internal object PortsTags {
    const val REFRESH = "preview_ports_refresh"
    const val CONTENT = "preview_ports_content"
    const val LOADING = "preview_ports_loading"
    const val REFRESHING = "preview_ports_refreshing"
    const val NOT_CONNECTED = "preview_ports_not_connected"
    const val ERROR = "preview_ports_error"
    const val EMPTY = "preview_ports_empty"
    const val UNSUPPORTED = "preview_ports_unsupported"
    const val MANUAL_FIELD = "preview_ports_manual_field"
    const val MANUAL_PROBLEM = "preview_ports_manual_problem"
    const val MANUAL_OPEN = "preview_ports_manual_open"

    /** The row of one listed port. */
    fun port(port: Int): String = "preview_ports_port:$port"
}
