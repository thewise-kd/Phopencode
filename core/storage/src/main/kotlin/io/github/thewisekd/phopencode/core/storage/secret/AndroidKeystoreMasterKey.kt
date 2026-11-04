package io.github.thewisekd.phopencode.core.storage.secret

import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeystore
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * An AES-256-GCM key in the Android Keystore under [alias]. The key never leaves the Keystore, and
 * Android deletes it when the app is uninstalled, so files that outlive the installation (for
 * example restored from elsewhere) cannot be decrypted by a new one.
 */
internal class AndroidKeystoreMasterKey(private val alias: String) : MasterKey {
    override fun aeadOrNull(): Aead? = keystore {
        if (AndroidKeystore.hasKey(alias)) AndroidKeystore.getAead(alias) else null
    }

    override fun create(): Aead = keystore {
        if (AndroidKeystore.hasKey(alias)) AndroidKeystore.deleteKey(alias)
        AndroidKeystore.generateNewAes256GcmKey(alias)
        AndroidKeystore.getAead(alias)
    }

    override fun delete() = keystore {
        if (AndroidKeystore.hasKey(alias)) AndroidKeystore.deleteKey(alias)
    }

    override fun toString(): String = "AndroidKeystoreMasterKey"

    private inline fun <T> keystore(block: () -> T): T = try {
        block()
    } catch (e: GeneralSecurityException) {
        throw IOException("Android Keystore failed", e)
    } catch (e: ProviderException) {
        throw IOException("Android Keystore failed", e)
    }
}
