package io.github.thewisekd.phopencode.feature.chat.ui.timeline

import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey

/** The timeline's scrolling list. */
internal const val TIMELINE_LIST_TAG = "chat_timeline:list"

/** The jump-to-latest button. */
internal const val TIMELINE_JUMP_TAG = "chat_timeline:jump"

/** The bubble around a prompt's text. */
internal const val TIMELINE_USER_BUBBLE_TAG = "chat_timeline:user_bubble"

/** The body of a reply's text. */
internal const val TIMELINE_ASSISTANT_BODY_TAG = "chat_timeline:assistant_body"

/** The chip of an execution that did not succeed: failed, stopped or of unknown end. */
internal const val TIMELINE_TURN_CHIP_TAG = "chat_timeline:turn_chip"

/** The root of the item under [key], the whole row the list places. */
internal fun timelineItemTag(key: TimelineKey): String = "chat_timeline:item:${key.value}"
