package io.github.thewisekd.phopencode.feature.connect.ui.serverform

import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormField
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormFieldError.Invalid
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormFieldError.Required
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi passes 26 only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishServerFormScreenTest : ServerFormLanguageContract() {
    override val strings = ExpectedServerFormStrings(
        titleNew = "Add server",
        titleEdit = "Edit server",
        name = "Name",
        host = "Host",
        port = "SSH port",
        username = "User name",
        authPasswordLabel = "Log in with a password",
        authKeyLabel = "Log in with an SSH key",
        passwordLabel = "Password",
        passwordKeepHint = "Leave empty to keep the saved password",
        opencodePasswordLabel = "opencode serve password",
        opencodePasswordClearLabel = "Remove the saved opencode serve password",
        opencodePortLabel = "opencode port",
        opencodePortHelp = "Optional. Leave empty to detect the running opencode server automatically.",
        keysEmpty = "No SSH keys yet. Add one to log in with a key.",
        manageKeys = "Manage keys",
        loading = "Loading server…",
        save = "Save",
        saving = "Saving…",
        cancel = "Cancel",
        fieldErrors = mapOf(
            (ServerFormField.Name to Required) to "Enter a name",
            (ServerFormField.Name to Invalid) to "This name cannot be used",
            (ServerFormField.Host to Required) to "Enter a host name or address",
            (ServerFormField.Host to Invalid) to "Not a valid host name or address",
            (ServerFormField.Port to Required) to "Enter a port",
            (ServerFormField.Port to Invalid) to "Enter a port from 1 to 65535",
            (ServerFormField.Username to Required) to "Enter a user name",
            (ServerFormField.Username to Invalid) to "Not a valid SSH user name",
            (ServerFormField.Password to Required) to "Enter the password",
            (ServerFormField.Password to Invalid) to "This password cannot be used",
            (ServerFormField.Key to Required) to "Choose a key",
            (ServerFormField.Key to Invalid) to "This key can no longer be used",
            (ServerFormField.OpencodePort to Required) to "Enter a port from 1 to 65535, or leave it empty",
            (ServerFormField.OpencodePort to Invalid) to "Enter a port from 1 to 65535, or leave it empty",
        ),
        formErrors = mapOf(
            ServerFormError.LoadFailed to "Could not read the server or the keys",
            ServerFormError.NotFound to "This server no longer exists",
            ServerFormError.SaveFailed to "Could not save the server. Nothing was changed.",
        ),
    )
}
