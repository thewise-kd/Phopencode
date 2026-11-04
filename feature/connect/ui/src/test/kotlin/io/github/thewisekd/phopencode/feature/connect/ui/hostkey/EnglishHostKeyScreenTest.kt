package io.github.thewisekd.phopencode.feature.connect.ui.hostkey

import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyError
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishHostKeyScreenTest : HostKeyScreenLanguageContract() {
    override val strings = ExpectedHostKeyStrings(
        title = "Server key",
        loading = "Waiting for the server's key…",
        none = "There is no server key to check.",
        deciding = "Saving your decision…",
        type = { "Key type: $it" },
        fingerprintLabel = "SHA-256 fingerprint",
        firstTitle = "Check this server's key",
        firstMessage = "This is the first connection to this server. Compare the fingerprint below with the one " +
            "the server's administrator gives you, for example the output of ssh-keygen -l on the server. " +
            "Approving saves this key: if the server later presents another key, the connection stops.",
        approve = "Approve and connect",
        reject = "Reject",
        changedTitle = "Warning: this server's key has changed",
        changedMessage = "The server presented a different key from the one you approved. Someone may be " +
            "intercepting the connection (a man-in-the-middle attack). It can also happen after the server was " +
            "reinstalled or its keys were replaced. The connection has been stopped. Accept the new key only if " +
            "the server's administrator confirms the change.",
        previousLabel = "Previously approved fingerprint (SHA-256)",
        newLabel = "New fingerprint (SHA-256)",
        changedReject = "Reject and stay disconnected",
        changedAccept = "Trust the new key…",
        confirmTitle = "Replace the approved key?",
        confirmMessage = "Do this only if you have checked the new fingerprint with the server's administrator. " +
            "An attacker in the middle could read everything you send to this server, passwords included.",
        confirmAccept = "Replace key",
        errors = mapOf(
            HostKeyError.NoRequest to
                "The connection no longer waits for a decision on this key. Connect again to check it.",
            HostKeyError.ServerMissing to "This server's profile no longer exists, so its key cannot be checked.",
            HostKeyError.StorageFailed to "The approved server keys could not be read or saved. Nothing was changed.",
            HostKeyError.Superseded to
                "The approved key changed while you were deciding. Nothing was replaced; connect again to check the key.",
        ),
    )
}
