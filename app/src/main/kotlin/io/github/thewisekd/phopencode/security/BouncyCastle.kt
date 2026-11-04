package io.github.thewisekd.phopencode.security

import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Android registers its own, stripped copy of BouncyCastle under the name `BC`
 * (`com.android.org.bouncycastle`), without the Ed25519 and X25519 sshj needs. sshj asks for `BC`
 * by name once that name is registered, so the full provider from the app's dependencies has to
 * take its place before the first SSH connection. [install] does that; the application calls it
 * first thing at start.
 *
 * The full provider takes the stripped one's place in the order, see [ProviderReplacement].
 * Safe to call from any thread and any number of times.
 */
object BouncyCastle {
    /** @return The full provider, now registered as `BC`. */
    fun install(): BouncyCastleProvider =
        ProviderReplacement.replace(BouncyCastleProvider.PROVIDER_NAME, BouncyCastleProvider::class.java) {
            BouncyCastleProvider()
        }
}
