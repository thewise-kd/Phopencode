package io.github.thewisekd.phopencode.feature.connect.ui.serverform

import io.github.thewisekd.phopencode.core.storage_api.SshKeyId

/** Test tags of the server form's controls. */
internal object ServerFormTags {
    const val NAME = "serverform:name"
    const val HOST = "serverform:host"
    const val PORT = "serverform:port"
    const val USERNAME = "serverform:username"
    const val AUTH_PASSWORD_OPTION = "serverform:auth-password"
    const val AUTH_KEY_OPTION = "serverform:auth-key"
    const val PASSWORD_FIELD = "serverform:password"
    const val MANAGE_KEYS = "serverform:manage-keys"
    const val OPENCODE_PASSWORD_FIELD = "serverform:opencode-password"
    const val CLEAR_OPENCODE_PASSWORD_BUTTON = "serverform:clear-opencode-password"
    const val SAVE = "serverform:save"
    const val CANCEL = "serverform:cancel"

    fun key(id: SshKeyId): String = "serverform:key:${id.value}"
}
