package io.github.thewisekd.phopencode.core.storage.secret

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.ProviderException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A [SecretStore] that keeps one AES-256-GCM encrypted file per entry under [root].
 *
 * Layout: `keyset` holds a Tink keyset encrypted with [masterKey]; `entries/<name>` holds one
 * secret encrypted with that keyset. An entry's name is the SHA-256 of its reference, so neither
 * names nor contents on disk tell how secrets are named, and no reference can reach outside
 * [root]. The reference is also the associated data of the entry's ciphertext, so an entry copied
 * under another name does not decrypt.
 *
 * Unreadable entries: once [masterKey] is gone, which is what removing the installation does to a
 * Keystore key, the keyset cannot be opened and every entry reads as
 * [SecretReadResult.Unreadable]. The next [put] removes that keyset, then starts a new master key
 * and keyset; older entries stay unreadable until they are replaced or deleted.
 *
 * Failures that retrying may cure are [IOException]s: file system errors, an unreachable key
 * store, and a keyset that the existing master key fails to open. The last one is not treated as
 * lost, so that a passing Keystore error never throws every secret away; if the keyset is damaged
 * for good, [deleteAll] clears it.
 *
 * One instance per [root] in a process; instances do not coordinate with each other.
 */
internal class TinkSecretStore(
    private val root: File,
    private val masterKey: MasterKey,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SecretStore {
    private val mutex = Mutex()
    private val entriesDir = File(root, ENTRIES_DIR)

    /** The keyset's primitive once opened. Guarded by [mutex]. */
    private var data: Aead? = null

    val keysetFile: File = File(root, KEYSET_FILE)

    fun entryFile(ref: SecretRef): File = File(entriesDir, entryName(ref))

    override suspend fun put(ref: SecretRef, value: SecretValue) = locked {
        val aead = dataAead(create = true)!!
        val plain = value.revealBytes()
        val sealed = try {
            aead.encrypt(plain, associatedData(ref))
        } catch (e: GeneralSecurityException) {
            throw IOException("Cannot encrypt the entry", e)
        } finally {
            plain.fill(0)
        }
        writeAtomically(entryFile(ref).toPath(), sealed)
    }

    override suspend fun read(ref: SecretRef): SecretReadResult = locked {
        val sealed = readOrNull(entryFile(ref).toPath()) ?: return@locked SecretReadResult.NotFound
        val aead = dataAead(create = false) ?: return@locked SecretReadResult.Unreadable
        val plain = try {
            aead.decrypt(sealed, associatedData(ref))
        } catch (_: GeneralSecurityException) {
            // Written with a keyset this installation no longer has, or damaged.
            return@locked SecretReadResult.Unreadable
        }
        try {
            SecretReadResult.Found(SecretValue.ofBytes(plain))
        } finally {
            plain.fill(0)
        }
    }

    override suspend fun delete(ref: SecretRef) = locked {
        Files.deleteIfExists(entryFile(ref).toPath())
        Unit
    }

    override suspend fun deleteAll() = locked {
        val entries = entriesDir.toPath()
        val children = try {
            Files.newDirectoryStream(entries).use(DirectoryStream<Path>::toList)
        } catch (_: NoSuchFileException) {
            emptyList()
        }
        children.forEach(Files::deleteIfExists)
        Files.deleteIfExists(entries)
        Files.deleteIfExists(keysetFile.toPath())
        data = null
        masterKey.delete()
    }

    override fun toString(): String = "TinkSecretStore"

    private suspend fun <T> locked(block: () -> T): T = withContext(io) { mutex.withLock { block() } }

    /**
     * The keyset's primitive. Without a usable keyset, null when [create] is false, and a new
     * keyset otherwise.
     */
    private fun dataAead(create: Boolean): Aead? {
        data?.let { return it }
        val sealedKeyset = readOrNull(keysetFile.toPath())
        if (sealedKeyset != null) {
            val master = masterKey.aeadOrNull()
            if (master != null) {
                val handle = keystoreCall("Cannot open the keyset") {
                    TinkProtoKeysetFormat.parseEncryptedKeyset(sealedKeyset, master, KEYSET_ASSOCIATED_DATA, configuration())
                }
                return primitive(handle).also { data = it }
            }
        }
        if (!create) return null
        if (sealedKeyset != null) {
            // Its master key is gone, so nothing can open it again. Removed before a new master
            // key exists, so that a failure below never leaves an old keyset beside a new key,
            // which would look like a keyset its own key fails to open.
            Files.deleteIfExists(keysetFile.toPath())
        }
        return newKeyset().also { data = it }
    }

    private fun newKeyset(): Aead {
        val master = masterKey.aeadOrNull() ?: masterKey.create()
        val handle = cryptoCall { KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM, configuration()) }
        val sealed = keystoreCall("Cannot seal the keyset") {
            TinkProtoKeysetFormat.serializeEncryptedKeyset(handle, master, KEYSET_ASSOCIATED_DATA, configuration())
        }
        val aead = primitive(handle)
        writeAtomically(keysetFile.toPath(), sealed)
        return aead
    }

    private fun primitive(handle: KeysetHandle): Aead =
        cryptoCall { handle.getPrimitive(configuration(), Aead::class.java) }

    companion object {
        private const val KEYSET_FILE = "keyset"
        private const val ENTRIES_DIR = "entries"
        private const val TEMP_SUFFIX = ".tmp"
        private val KEYSET_ASSOCIATED_DATA = "phopencode.secret.keyset.v1".encodeToByteArray()
        private const val ENTRY_ASSOCIATED_PREFIX = "phopencode.secret.entry.v1:"

        private fun associatedData(ref: SecretRef): ByteArray = (ENTRY_ASSOCIATED_PREFIX + ref.id).encodeToByteArray()

        private fun entryName(ref: SecretRef): String =
            MessageDigest.getInstance("SHA-256").digest(ref.id.encodeToByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun configuration() = cryptoCall {
            AeadConfig.register()
            RegistryConfiguration.get()
        }

        /** A local Tink call: a failure is a bug or a broken runtime, not a passing condition. */
        private inline fun <T> cryptoCall(block: () -> T): T = try {
            block()
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Tink failed", e)
        }

        /** A call that reaches the key store; its failures may pass. */
        private inline fun <T> keystoreCall(message: String, block: () -> T): T = try {
            block()
        } catch (e: GeneralSecurityException) {
            throw IOException(message, e)
        } catch (e: ProviderException) {
            throw IOException(message, e)
        }

        private fun readOrNull(path: Path): ByteArray? = try {
            Files.readAllBytes(path)
        } catch (_: NoSuchFileException) {
            null
        }

        /** Replaces [target] in one step, so that a crash never leaves half an entry. */
        private fun writeAtomically(target: Path, bytes: ByteArray) {
            val dir = target.parent
            Files.createDirectories(dir)
            val temp = Files.createTempFile(dir, target.fileName.toString(), TEMP_SUFFIX)
            try {
                FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }
}
