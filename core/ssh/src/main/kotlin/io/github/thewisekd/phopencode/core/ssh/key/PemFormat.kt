package io.github.thewisekd.phopencode.core.ssh.key

import java.io.CharArrayReader
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.ECNamedDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo

/**
 * Older key files: PKCS#1 (`BEGIN RSA PRIVATE KEY`, ssh-keygen before OpenSSH 7.8 and `-m PEM`),
 * SEC1 (`BEGIN EC PRIVATE KEY`) and PKCS#8 (`BEGIN PRIVATE KEY`, `BEGIN ENCRYPTED PRIVATE KEY`),
 * read through BouncyCastle. BouncyCastle keeps its own copies of the key, which cannot be wiped.
 */
internal object PemFormat {
    /** A private instance: registering a provider globally would change crypto for the whole app. */
    private val provider by lazy { BouncyCastleProvider() }

    /** @return The public key, without a comment because PEM has none, and whether it was encrypted. */
    fun read(text: CharArray, passphrase: CharArray?): Pair<SshPublicKey, Boolean> {
        val parsed = try {
            PEMParser(CharArrayReader(text)).use { it.readObject() }
        } catch (failure: Exception) {
            throw SshKeyException.Malformed()
        }
        val encrypted = parsed is PEMEncryptedKeyPair || parsed is PKCS8EncryptedPrivateKeyInfo
        if (encrypted && (passphrase == null || passphrase.isEmpty())) throw SshKeyException.PassphraseRequired()
        val info: PrivateKeyInfo = when (parsed) {
            is PEMKeyPair -> parsed.privateKeyInfo
            is PrivateKeyInfo -> parsed
            is PEMEncryptedKeyPair -> decrypt(passphrase) { parsed.decryptKeyPair(JcePEMDecryptorProviderBuilder().setProvider(provider).build(it)).privateKeyInfo }
            is PKCS8EncryptedPrivateKeyInfo -> decrypt(passphrase) {
                parsed.decryptPrivateKeyInfo(JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider(provider).build(it))
            }
            else -> throw SshKeyException.Malformed()
        } ?: throw SshKeyException.Malformed()
        val key: AsymmetricKeyParameter = try {
            PrivateKeyFactory.createKey(info)
        } catch (failure: Exception) {
            throw if (encrypted) SshKeyException.WrongPassphrase() else SshKeyException.Malformed()
        }
        val blob = publicBlob(key)
        return SshPublicKey(PublicBlob.parse(blob).type, blob) to encrypted
    }

    /** Works on a copy of the passphrase and wipes it; any failure counts as a wrong passphrase. */
    private fun <T> decrypt(passphrase: CharArray?, block: (CharArray) -> T): T {
        val copy = checkNotNull(passphrase).copyOf()
        return try {
            block(copy)
        } catch (failure: Exception) {
            throw SshKeyException.WrongPassphrase()
        } finally {
            copy.fill('\u0000')
        }
    }

    private fun publicBlob(key: AsymmetricKeyParameter): ByteArray = when (key) {
        is RSAPrivateCrtKeyParameters -> {
            RsaConsistency.require(key.modulus, key.publicExponent, key.exponent, key.qInv, key.p, key.q)
            PublicBlob.rsa(key.publicExponent, key.modulus)
        }
        is Ed25519PrivateKeyParameters -> PublicBlob.ed25519(key.generatePublicKey().encoded)
        is ECPrivateKeyParameters -> {
            val domain = key.parameters as? ECNamedDomainParameters ?: throw SshKeyException.Unsupported()
            val curve = PublicBlob.sshCurveName(domain.name) ?: throw SshKeyException.Unsupported()
            PublicBlob.ecdsa(curve, domain.g.multiply(key.d).normalize().getEncoded(false))
        }
        else -> throw SshKeyException.Unsupported()
    }
}
