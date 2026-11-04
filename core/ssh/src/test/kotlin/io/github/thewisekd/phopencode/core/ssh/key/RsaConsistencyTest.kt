package io.github.thewisekd.phopencode.core.ssh.key

import java.io.StringWriter
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.RSAPrivateKey
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter

/**
 * An RSA private key must be consistent in all its parts, not only in n = p·q: a key with a damaged
 * private exponent would read fine and then fail at login. This holds for every file format.
 */
class RsaConsistencyTest {
    private val parts = OpenSshRsa.parts()

    private enum class Format(val write: (OpenSshRsa.Parts) -> ByteArray) {
        OpenSsh(OpenSshRsa::file),
        Pkcs1(RsaPem::pkcs1),
        Pkcs8(RsaPem::pkcs8),
    }

    @Test
    fun `an intact key written from its parts reads in every format`() {
        Format.entries.forEach { format ->
            val info = SshKeys.read(format.write(parts))
            assertEquals("ssh-rsa", info.publicKey.type, format.name)
            assertEquals(MinaKeysRsa.publicLine(parts), SshKeygen.typeAndKey(info.publicKey.authorizedKeysLine()), format.name)
        }
    }

    @Test
    fun `a damaged private exponent fails with Malformed`() = assertMalformed(parts.copy(d = parts.d + TWO))

    @Test
    fun `a private exponent of zero fails with Malformed`() = assertMalformed(parts.copy(d = BigInteger.ZERO))

    @Test
    fun `a damaged CRT coefficient fails with Malformed`() = assertMalformed(parts.copy(iqmp = parts.iqmp + BigInteger.ONE))

    @Test
    fun `swapped primes, which keep p·q = n, fail with Malformed`() = assertMalformed(parts.copy(p = parts.q, q = parts.p))

    @Test
    fun `a damaged prime fails with Malformed`() = assertMalformed(parts.copy(p = parts.p + TWO))

    private fun assertMalformed(damaged: OpenSshRsa.Parts) {
        Format.entries.forEach { format ->
            assertFailsWith<SshKeyException.Malformed>(format.name) { SshKeys.read(format.write(damaged)) }
        }
    }

    private fun OpenSshRsa.Parts.copy(
        d: BigInteger = this.d,
        iqmp: BigInteger = this.iqmp,
        p: BigInteger = this.p,
        q: BigInteger = this.q,
    ) = OpenSshRsa.Parts(n, e, d, iqmp, p, q)

    private companion object {
        val TWO: BigInteger = BigInteger.valueOf(2)
    }
}

/** The public line of an RSA key from its parts, built by MINA. */
private object MinaKeysRsa {
    fun publicLine(parts: OpenSshRsa.Parts): String {
        val key = java.security.KeyFactory.getInstance("RSA").generatePublic(java.security.spec.RSAPublicKeySpec(parts.n, parts.e))
        return MinaKeys.publicLine(key)
    }
}

/** RSA keys in PEM written from their parts, damaged ones included. */
private object RsaPem {
    private val TWO: BigInteger = BigInteger.valueOf(2)

    fun asn1(parts: OpenSshRsa.Parts): RSAPrivateKey = with(parts) {
        // A zero exponent has no CRT exponents; any value keeps the structure well-formed.
        val one = BigInteger.ONE
        RSAPrivateKey(n, e, d, p, q, d.mod(p.max(TWO) - one), d.mod(q.max(TWO) - one), iqmp)
    }

    fun pem(type: String, der: ByteArray): ByteArray {
        val out = StringWriter()
        PemWriter(out).use { it.writeObject(PemObject(type, der)) }
        return out.toString().encodeToByteArray()
    }

    fun pkcs1(parts: OpenSshRsa.Parts): ByteArray = pem("RSA PRIVATE" + " KEY", asn1(parts).encoded)

    fun pkcs8(parts: OpenSshRsa.Parts): ByteArray {
        val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE)
        return pem("PRIVATE" + " KEY", PrivateKeyInfo(algorithm, asn1(parts)).encoded)
    }
}
