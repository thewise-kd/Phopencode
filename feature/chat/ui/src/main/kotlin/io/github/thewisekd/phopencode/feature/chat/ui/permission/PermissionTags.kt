package io.github.thewisekd.phopencode.feature.chat.ui.permission

/** The tag on the accent strip across the card's top, which holds the title and who asks. */
internal const val PERMISSION_HEADER_TAG = "permission:header"

/** The tag on the card's title. */
internal const val PERMISSION_TITLE_TAG = "permission:title"

/** The tag on the line that says whether this session or a subagent asks. */
internal const val PERMISSION_ASKER_TAG = "permission:asker"

/** The tag on the action, as the server names it. */
internal const val PERMISSION_ACTION_TAG = "permission:action"

/** The tag on the action said in words, drawn only for actions the app knows. */
internal const val PERMISSION_ACTION_MEANING_TAG = "permission:action_meaning"

/** The tag on the note drawn when the request names no resource. */
internal const val PERMISSION_NO_RESOURCES_TAG = "permission:no_resources"

/** The tag on the server's own wording of the request. */
internal const val PERMISSION_MESSAGE_TAG = "permission:message"

/** The tags on the three choices. */
internal const val PERMISSION_ONCE_TAG = "permission:once"
internal const val PERMISSION_ALWAYS_TAG = "permission:always"
internal const val PERMISSION_REJECT_TAG = "permission:reject"

/** The tags of the confirmation `always` waits for. */
internal const val PERMISSION_CONFIRM_TITLE_TAG = "permission:confirm_title"
internal const val PERMISSION_CONFIRM_TAG = "permission:confirm"
internal const val PERMISSION_WILDCARD_TAG = "permission:wildcard"
internal const val PERMISSION_CONFIRM_ALWAYS_TAG = "permission:confirm_always"
internal const val PERMISSION_CANCEL_ALWAYS_TAG = "permission:cancel_always"

/** The tags of the rejection message. */
internal const val PERMISSION_REJECT_FIELD_TAG = "permission:reject_field"
internal const val PERMISSION_SEND_REJECT_TAG = "permission:send_reject"
internal const val PERMISSION_CANCEL_REJECT_TAG = "permission:cancel_reject"

/** The tag on the note that a decision is on its way. */
internal const val PERMISSION_SENDING_TAG = "permission:sending"

/** The tag on the failed reply's banner. */
internal const val PERMISSION_ERROR_TAG = "permission:error"

/** The tag on the [index]th resource the request applies to. */
internal fun permissionResourceTag(index: Int): String = "permission:resource:$index"

/** The tag on the [index]th pattern an `always` decision would save. */
internal fun permissionSaveTag(index: Int): String = "permission:save:$index"
