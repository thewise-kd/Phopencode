package io.github.thewisekd.phopencode.core.ssh.key

import java.math.BigInteger

/** One check of an RSA private key for every file format, so that a key that reads can also sign. */
internal object RsaConsistency {
    /** @throws SshKeyException.Malformed unless all parts of the key agree. */
    fun require(n: BigInteger, e: BigInteger, d: BigInteger, iqmp: BigInteger, p: BigInteger, q: BigInteger) {
        val one = BigInteger.ONE
        val consistent = e > one && p > one && q > one && p.multiply(q) == n &&
            d.signum() > 0 && d < n && d.multiply(e).mod(lcm(p - one, q - one)) == one &&
            iqmp.signum() > 0 && iqmp < p && iqmp.multiply(q).mod(p) == one
        if (!consistent) throw SshKeyException.Malformed()
    }

    private fun lcm(a: BigInteger, b: BigInteger): BigInteger = a.divide(a.gcd(b)).multiply(b)
}
