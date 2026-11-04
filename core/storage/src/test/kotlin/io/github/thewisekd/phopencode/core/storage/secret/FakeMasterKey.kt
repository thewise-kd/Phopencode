package io.github.thewisekd.phopencode.core.storage.secret

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.io.IOException

/**
 * A master key in memory, standing in for the Android Keystore on the JVM. [failing] makes the
 * Keystore unreachable; [delete] is what removing the installation does to the real key.
 */
internal class FakeMasterKey : MasterKey {
    @Volatile
    private var key: Aead? = null

    @Volatile
    var failing = false

    val exists: Boolean
        get() = key != null

    override fun aeadOrNull(): Aead? {
        failIfFailing()
        return key
    }

    /** Runs right after [create] made a key, to fail what the store does next. */
    @Volatile
    var afterCreate: () -> Unit = {}

    override fun create(): Aead {
        failIfFailing()
        return newAead().also {
            key = it
            afterCreate()
        }
    }

    /** Puts a different key under the same name, which cannot open what the old one sealed. */
    fun replaceWithAnotherKey() {
        key = newAead()
    }

    override fun delete() {
        failIfFailing()
        key = null
    }

    private fun failIfFailing() {
        if (failing) throw IOException("Fake Keystore is unavailable")
    }

    companion object {
        fun newAead(): Aead {
            AeadConfig.register()
            return KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }
    }
}
