package io.github.thewisekd.phopencode.feature.git.ui.status

import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusState

/** The groups of the list, in the order the screen shows them: what blocks a commit comes first. */
internal enum class StatusGroup {
    Conflicted,
    Staged,
    Unstaged,
    Untracked,
}

/** A group-wide action, shown on one group's header. */
internal enum class BulkAction { StageAll, UnstageAll }

internal fun StatusState.changes(group: StatusGroup): List<GitFileChange> = when (group) {
    StatusGroup.Conflicted -> conflicted
    StatusGroup.Staged -> staged
    StatusGroup.Unstaged -> unstaged
    StatusGroup.Untracked -> untracked
}

/**
 * The action [group]'s header carries: unstage all on the staged group; stage all, which takes the
 * unstaged and the untracked rows together, on the first of those two that has rows. Conflicted rows
 * are staged one by one, each a decision that the conflict is resolved.
 */
internal fun StatusState.bulkAction(group: StatusGroup): BulkAction? = when (group) {
    StatusGroup.Conflicted -> null
    StatusGroup.Staged -> BulkAction.UnstageAll.takeIf { staged.isNotEmpty() }
    StatusGroup.Unstaged -> BulkAction.StageAll.takeIf { unstaged.isNotEmpty() }
    StatusGroup.Untracked -> BulkAction.StageAll.takeIf { unstaged.isEmpty() && untracked.isNotEmpty() }
}
