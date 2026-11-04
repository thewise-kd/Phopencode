package io.github.thewisekd.phopencode.core.storage.secret

import android.content.Context
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import java.io.File

/** The Keystore alias of the master key. Changing it makes every stored secret unreadable. */
private const val MASTER_KEY_ALIAS = "phopencode.secret.master.v1"

/** Directory under `noBackupFilesDir`, which Android never backs up or transfers to another device. */
private const val DIRECTORY = "secrets"

/**
 * The app's [SecretStore]: Tink AES-256-GCM, its keyset wrapped by an Android Keystore key, in a
 * directory that no backup includes. Create one per process.
 */
fun androidSecretStore(context: Context): SecretStore = TinkSecretStore(
    root = File(context.applicationContext.noBackupFilesDir, DIRECTORY),
    masterKey = AndroidKeystoreMasterKey(MASTER_KEY_ALIAS),
)
