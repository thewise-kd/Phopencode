package io.github.thewisekd.phopencode.feature.preview.ui.notice

/** Test tags of the live preview's first-visit notice. */
internal object NoticeTags {
    const val BLOCKER = "preview_notice_blocker"
    const val DIALOG = "preview_notice_dialog"
    const val ICON = "preview_notice_icon"
    const val BODY = "preview_notice_body"
    const val SAVE_FAILED = "preview_notice_save_failed"
    const val ACCEPT = "preview_notice_accept"
    const val NOT_NOW = "preview_notice_not_now"

    /** One point of the notice, counted from 0 in the order shown. */
    fun point(index: Int): String = "preview_notice_point:$index"
}
