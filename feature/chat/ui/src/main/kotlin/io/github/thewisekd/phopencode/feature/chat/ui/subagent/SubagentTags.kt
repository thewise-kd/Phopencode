package io.github.thewisekd.phopencode.feature.chat.ui.subagent

import io.github.thewisekd.phopencode.core.model.SessionId

/** Test tags of the subagent badge, header and list. A row's tag holds all of its texts. */
internal object SubagentTags {
    /** The still dot that marks the badge live while a subagent runs. */
    const val LIVE_DOT: String = "subagent:live-dot"
    const val HEADER_BACK: String = "subagent:header:back"
    const val HEADER_LIST: String = "subagent:header:list"
    const val SUMMARY: String = "subagent:summary"

    fun row(sessionId: SessionId): String = "subagent:row:${sessionId.value}"
}
