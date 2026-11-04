package io.github.thewisekd.phopencode.feature.connect.ui.keys

import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysError
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishKeysScreenTest : KeysScreenLanguageContract() {
    override val strings = ExpectedKeysStrings(
        title = "SSH keys",
        loading = "Loading keys…",
        emptyTitle = "No SSH keys yet",
        emptyMessage = "Generate a new key or import a private key file.",
        import = "Import",
        generate = "Generate",
        type = { "Type: $it" },
        fingerprint = { "SHA-256 fingerprint: $it" },
        copyDescription = { "Copy the public key of $it" },
        deleteDescription = { "Delete $it" },
        deleteTitle = "Delete this key?",
        deleteMessage = { "$it will be removed from this phone. Servers that trust its public key are not changed." },
        delete = "Delete",
        generateTitle = "Generate a key",
        generateName = "Name",
        generatePassphraseLabel = "Passphrase (optional)",
        generateConfirm = "Create key",
        passphraseTitle = "Key passphrase",
        passphraseMessage = "This key file is encrypted. Enter its passphrase to import it.",
        passphraseSubmit = "Unlock",
        errors = mapOf(
            KeysError.LoadFailed to "The keys could not be loaded.",
            KeysError.GenerateFailed to "The key could not be generated. Try again.",
            KeysError.ImportMalformed to "This file is not a private key, or it is damaged.",
            KeysError.ImportUnsupported to "This key type or encryption is not supported.",
            KeysError.WrongPassphrase to "Wrong passphrase. Try again.",
            KeysError.KeyInUse to "A saved server still logs in with this key. Choose another key for that server first.",
            KeysError.SaveFailed to "The key could not be saved on this phone.",
            KeysError.DeleteFailed to "The key could not be deleted. Try again.",
        ),
        fileErrors = mapOf(
            KeyFileError.TooLarge to "The file is too large to be a private key.",
            KeyFileError.Empty to "The file is empty.",
            KeyFileError.Unreadable to "The file could not be read.",
        ),
    )
}
