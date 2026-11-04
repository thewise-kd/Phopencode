package io.github.thewisekd.phopencode.core.storage.secret

import com.google.crypto.tink.Aead

/**
 * The key that wraps the store's keyset. On a device it lives in the Android Keystore, which
 * removes it together with the installation. Every function throws [java.io.IOException] when the
 * key store cannot be reached right now.
 */
internal interface MasterKey {
    /** The key, or null if there is none. */
    fun aeadOrNull(): Aead?

    /** Generates a new key, replacing any existing one. */
    fun create(): Aead

    /** Removes the key. Does nothing if there is none. */
    fun delete()
}
