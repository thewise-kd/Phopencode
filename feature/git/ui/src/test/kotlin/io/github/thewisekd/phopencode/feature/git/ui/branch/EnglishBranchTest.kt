package io.github.thewisekd.phopencode.feature.git.ui.branch

import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchNameProblem
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishBranchTest : BranchLanguageContract() {
    override val strings = ExpectedBranchStrings(
        title = "Branches",
        refresh = "Refresh",
        create = "New branch",
        loading = "Reading the branches…",
        busy = mapOf(
            BranchOp.Create to "Creating the branch…",
            BranchOp.Switch to "Switching branch…",
            BranchOp.Delete to "Deleting the branch…",
        ),
        search = "Search branches",
        searchClear = "Clear the search",
        local = "Local",
        remote = "Remote",
        current = "Current branch",
        delete = { "Delete $it" },
        tracking = { "Tracking $it" },
        ahead = { "$it ahead" },
        behind = { "$it behind" },
        upToDate = "Up to date",
        noUpstream = "No upstream branch",
        emptyTitle = "No branches",
        noMatchTitle = "No matching branches",
        dirtyTitle = "Uncommitted changes",
        dirtyConfirm = "Switch anyway",
        cancel = "Cancel",
        deleteTitle = "Delete the branch?",
        deleteConfirm = "Delete",
        unmergedTitle = "Branch not merged",
        unmergedConfirm = "Delete anyway",
        createTitle = "New branch",
        createName = "Branch name",
        createFrom = { "Starts from $it" },
        createFromHead = "Starts from the current commit",
        createSwitchAfter = "Switch to it once created",
        createSubmit = "Create",
        problems = mapOf(
            BranchNameProblem.Blank to "Enter a name.",
            BranchNameProblem.Invalid to "Git does not allow this name.",
            BranchNameProblem.Taken to "A local branch already has this name.",
        ),
        retry = "Try again",
        errors = mapOf(
            GitFailure.NotConnected to "Not connected to the server. Go back and connect again.",
            GitFailure.Unreachable to "The server could not be reached.",
            GitFailure.TimedOut to "Git took too long to answer.",
            GitFailure.Unauthorized to "The server refused the login. Go back and connect again.",
            GitFailure.OutputTooLarge to "The branch list is too large to show on the phone.",
            GitFailure.NotRepository to "This folder is not in a git repository.",
            GitFailure.GitMissing to "Git is not installed on the server.",
            GitFailure.Rejected to "The branch name was refused.",
            GitFailure.NotFound to "The branch is no longer there.",
            GitFailure.WouldOverwrite to
                "The switch would overwrite uncommitted changes, so git changed nothing. Commit or stash them first.",
            GitFailure.Conflict to "The repository has unresolved conflicts.",
        ),
        readFallback = "The branches could not be listed.",
        actionFallbacks = mapOf(
            BranchOp.Create to "The branch could not be created.",
            BranchOp.Switch to "Could not switch to the branch.",
            BranchOp.Delete to "The branch could not be deleted.",
        ),
    )
}
