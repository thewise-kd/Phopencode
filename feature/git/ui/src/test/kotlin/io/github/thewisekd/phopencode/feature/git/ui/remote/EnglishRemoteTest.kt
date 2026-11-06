package io.github.thewisekd.phopencode.feature.git.ui.remote

import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteOp
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishRemoteTest : RemoteLanguageContract() {
    override val strings = ExpectedRemoteStrings(
        title = "Remote",
        refresh = "Refresh",
        loading = "Reading the remotes…",
        detached = { "Detached HEAD at $it" },
        tracking = { "Tracking $it" },
        ahead = { "$it ahead" },
        behind = { "$it behind" },
        upToDate = "Up to date",
        noUpstream = "No upstream branch; a push sets one",
        remote = { "Remote: $it" },
        chooseRemote = "Choose a remote",
        noRemotesTitle = "No remotes",
        fetch = "Fetch",
        pull = "Pull",
        pullHint = "Fast-forward only: never merges or rebases.",
        push = "Push",
        running = mapOf(
            RemoteOp.Fetch to "Fetching…",
            RemoteOp.Pull to "Pulling…",
            RemoteOp.Push to "Pushing…",
        ),
        cancel = "Cancel",
        confirmTitle = { "Push to $it?" },
        confirmMessage = { count, branch, remote ->
            if (count == 1) "Push 1 commit from $branch to $remote?" else "Push $count commits from $branch to $remote?"
        },
        confirmMessageUnknown = { branch, remote -> "Push $branch to $remote?" },
        confirmSetUpstream = { branch, remote -> "$branch has no upstream yet; the push makes $remote/$branch its upstream." },
        confirmNoForce = "The push is never forced: if the remote has newer commits, it is refused.",
        succeeded = mapOf(
            RemoteOp.Fetch to "Fetched from the remote.",
            RemoteOp.Pull to "Pulled; the branch moved forward.",
            RemoteOp.Push to "Pushed to the remote.",
        ),
        upToDateResult = mapOf(
            RemoteOp.Fetch to "Fetched; nothing new on the remote.",
            RemoteOp.Pull to "Already up to date; nothing to pull.",
            RemoteOp.Push to "Nothing to push; the remote already has these commits.",
        ),
        cancelled = mapOf(
            RemoteOp.Fetch to "Fetch cancelled.",
            RemoteOp.Pull to "Pull cancelled. Refresh to check the branch.",
            RemoteOp.Push to "Push cancelled. It may have reached the remote; refresh to check.",
        ),
        failed = mapOf(
            RemoteOp.Fetch to "Fetch failed.",
            RemoteOp.Pull to "Pull failed.",
            RemoteOp.Push to "Push failed.",
        ),
        dismiss = "Dismiss",
        explanations = mapOf(
            GitFailure.CredentialRequired to "The remote asked for a user name, password or passphrase, and the app " +
                "cannot answer prompts. Store the credentials on the server with a credential helper, or set up an " +
                "SSH key there.",
            GitFailure.NonFastForward to "The remote has commits this branch lacks: someone else pushed. Pull first, " +
                "then push again.",
            GitFailure.Diverged to "This branch and the remote both have new commits, so a fast-forward pull is not " +
                "possible. Merge or rebase on the server or a computer, then try again.",
            GitFailure.WouldOverwrite to "The pull would overwrite local changes; nothing was changed. Commit or " +
                "discard them first, then pull again.",
            GitFailure.Conflict to "The repository has unresolved merge conflicts. Resolve and commit them first, " +
                "then try again.",
            GitFailure.NotConnected to "Not connected to the server. Go back and connect again.",
            GitFailure.Unreachable to "The server could not be reached. Try again.",
            GitFailure.TimedOut to "Git took too long to answer. Try again.",
            GitFailure.Unauthorized to "The server refused the login. Go back and connect again.",
            GitFailure.OutputTooLarge to "Git printed more than the phone can show.",
            GitFailure.NotRepository to "This folder is not in a git repository.",
            GitFailure.GitMissing to "Git is not installed on the server.",
            GitFailure.Rejected to "The remote or branch name was refused.",
            GitFailure.NotFound to "The remote or branch is no longer there. Refresh and try again.",
        ),
        readFallback = "The remotes could not be read.",
        actionFallback = "Git reported an error. Check the repository on the server.",
        retry = "Try again",
    )
}
