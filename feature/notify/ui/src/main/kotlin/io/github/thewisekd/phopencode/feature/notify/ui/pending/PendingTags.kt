package io.github.thewisekd.phopencode.feature.notify.ui.pending

import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingItem

/** The scrolling list of pending items. */
internal const val PENDING_LIST_TAG = "notify_pending:list"

/** The empty state, shown when nothing waits. */
internal const val PENDING_EMPTY_TAG = "notify_pending:empty"

/** An item's card. Carries the item's key, its kind and ID, never server text. */
internal fun pendingItemTag(item: PendingItem): String = "notify_pending:item:${item.key}"
