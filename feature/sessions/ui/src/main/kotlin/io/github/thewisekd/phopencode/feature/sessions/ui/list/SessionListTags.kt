package io.github.thewisekd.phopencode.feature.sessions.ui.list

import io.github.thewisekd.phopencode.core.model.SessionId

/** The scrolling list of sessions, which a pull down refreshes. */
internal const val SESSION_LIST_TAG = "sessions_list:list"

/** A row's folder chip, shown only when the list spans every folder. Carries no path. */
internal const val SESSION_FOLDER_CHIP_TAG = "sessions_list:folder-chip"

/** The accent bar of a running session's card. Carries the session's ID, never its title. */
internal fun sessionRunningMarkTag(id: SessionId): String = "sessions_list:running:${id.value}"
