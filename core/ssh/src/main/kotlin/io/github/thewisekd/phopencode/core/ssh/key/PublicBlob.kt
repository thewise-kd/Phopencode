package io.github.thewisekd.phopencode.core.ssh.key

import java.math.BigInteger
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.math.ec.ECPoint

/** A public key in SSH wire format, taken apart. Only key types this module reads appear here. */
internal sealed interface PublicKeyData {
    val type: String

    class Ed25519(val key: ByteArray) : PublicKeyData {
        override val type: String get() = PublicBlob.ED25519
    }

    class Rsa(val e: BigInteger, val n: BigInteger) : PublicKeyData {
        override val type: String get() = PublicBlob.RSA
    }

    class Ecdsa(val curve: String, val q: ByteArray, val point: ECPoint, val parameters: X9ECParameters) : PublicKeyData {
        override val type: String get() = PublicBlob.ECDSA_PREFIX + curve
    }
}

internal object PublicBlob {
    const val ED25519 = "ssh-ed25519"
    const val RSA = "ssh-rsa"
    const val ECDSA_PREFIX = "ecdsa-sha2-"
    const val ED25519_KEY_SIZE = 32

    /** SSH curve names and the standard names BouncyCastle knows them by. */
    private val CURVES = mapOf("nistp256" to "secp256r1", "nistp384" to "secp384r1", "nistp521" to "secp521r1")

    /**
     * @throws SshKeyException.Unsupported for a key type this module does not read.
     * @throws SshKeyException.Malformed for anything that is not a valid key of its type.
     */
    fun parse(blob: ByteArray): PublicKeyData {
        val reader = WireReader(blob)
        val data = when (val type = reader.name()) {
            ED25519 -> {
                val key = reader.string()
                if (key.size != ED25519_KEY_SIZE) throw SshKeyException.Malformed()
                PublicKeyData.Ed25519(key)
            }

            RSA -> {
                val e = reader.mpint()
                val n = reader.mpint()
                if (e.signum() <= 0 || n.signum() <= 0) throw SshKeyException.Malformed()
                PublicKeyData.Rsa(e, n)
            }

            else -> {
                val curve = type.removePrefix(ECDSA_PREFIX)
                if (curve == type || curve !in CURVES) throw SshKeyException.Unsupported()
                if (reader.name() != curve) throw SshKeyException.Malformed()
                val q = reader.string()
                val parameters = curveParameters(curve)
                PublicKeyData.Ecdsa(curve, q, decodePoint(parameters, q), parameters)
            }
        }
        reader.requireEnd()
        return data
    }

    fun ed25519(key: ByteArray): ByteArray = wire {
        string(ED25519)
        string(key)
    }

    fun rsa(e: BigInteger, n: BigInteger): ByteArray = wire {
        string(RSA)
        mpint(e)
        mpint(n)
    }

    fun ecdsa(curve: String, q: ByteArray): ByteArray = wire {
        string(ECDSA_PREFIX + curve)
        string(curve)
        string(q)
    }

    /** The SSH name of the curve with this object identifier, or null for a curve SSH does not use. */
    fun sshCurveName(oid: ASN1ObjectIdentifier): String? = CURVES.entries.firstOrNull { SECNamedCurves.getOID(it.value) == oid }?.key

    fun curveParameters(curve: String): X9ECParameters =
        ECNamedCurveTable.getByName(CURVES[curve] ?: throw SshKeyException.Unsupported()) ?: throw SshKeyException.Unsupported()

    private fun decodePoint(parameters: X9ECParameters, q: ByteArray): ECPoint = try {
        parameters.curve.decodePoint(q).also { if (it.isInfinity || !it.isValid) throw SshKeyException.Malformed() }
    } catch (failure: IllegalArgumentException) {
        throw SshKeyException.Malformed()
    }
}
