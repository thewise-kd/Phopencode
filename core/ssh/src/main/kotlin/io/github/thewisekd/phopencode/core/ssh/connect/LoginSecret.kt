package io.github.thewisekd.phopencode.core.ssh.connect

import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import java.io.CharArrayReader
import java.security.KeyPair
import java.util.concurrent.CopyOnWriteArrayList
import net.schmizz.sshj.Config
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource

/**
 * A credential turned into an sshj login method for one connect. It works on copies, so the
 * caller's arrays stay the caller's, and [wipe] overwrites every copy it handed to sshj. It never
 * makes a `String` of a secret.
 */
internal class LoginSecret private constructor(val method: AuthMethod, private val copies: Copies) {
    fun wipe() = copies.wipe()

    override fun toString(): String = "LoginSecret(***)"

    private class Copies {
        private val arrays = CopyOnWriteArrayList<CharArray>()

        fun of(source: CharArray): CharArray = source.copyOf().also { arrays += it }

        fun wipe() = arrays.forEach { it.fill(ZERO) }
    }

    /** Hands sshj a fresh copy on each request, since sshj may keep or blank what it gets. */
    private class Finder(private val secret: CharArray?, private val copies: Copies) : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray? = secret?.let(copies::of)

        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }

    companion object {
        private const val ZERO = '\u0000'

        /**
         * Reads a key here, before any network work, so a key that cannot be read never opens a
         * connection.
         *
         * @throws SshException.AuthenticationFailed for a key that cannot be read, including a
         *   wrong or missing passphrase. No cause: a parser's message may quote the key.
         */
        fun of(credential: SshCredential, config: Config): LoginSecret {
            val copies = Copies()
            return when (credential) {
                is SshCredential.Password ->
                    LoginSecret(AuthPassword(Finder(copies.of(credential.password), copies)), copies)

                is SshCredential.PrivateKey -> try {
                    LoginSecret(AuthPublickey(KeyPairWrapper(readKey(credential, config, copies))), copies)
                } catch (failure: Exception) {
                    copies.wipe()
                    throw SshException.AuthenticationFailed()
                }
            }
        }

        private fun readKey(credential: SshCredential.PrivateKey, config: Config, copies: Copies): KeyPair {
            val text = copies.of(credential.privateKey)
            val passphrase = credential.passphrase?.let(copies::of)
            val format = KeyProviderUtil.detectKeyFileFormat(CharArrayReader(text), false)
            check(format != KeyFormat.Unknown) { "Unknown key format" }
            val provider = checkNotNull(Factory.Named.Util.create(config.fileKeyProviderFactories, format.toString())) {
                "No reader for the key format"
            }
            provider.init(CharArrayReader(text), Finder(passphrase, copies))
            // Both halves now, while the text is still there; the copies are wiped after login.
            return KeyPair(checkNotNull(provider.public), checkNotNull(provider.private))
        }
    }
}
