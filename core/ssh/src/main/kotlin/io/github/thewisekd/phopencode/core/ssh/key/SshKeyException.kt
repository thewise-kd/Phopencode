package io.github.thewisekd.phopencode.core.ssh.key

/**
 * Why a private key could not be read. Messages are fixed text and there is never a cause: a
 * parser's message can quote the key, and the passphrase must not reach a log either.
 */
sealed class SshKeyException(message: String) : Exception(message) {
    /** The input is not a private key file, or is damaged. */
    class Malformed : SshKeyException("Not a readable private key")

    /** A real key file, but of a key type, cipher or key derivation this app does not read. */
    class Unsupported : SshKeyException("Unsupported key type or encryption")

    /** The key is encrypted and no passphrase, or an empty one, was given. */
    class PassphraseRequired : SshKeyException("The key needs a passphrase")

    /** The key is encrypted and the passphrase does not open it. */
    class WrongPassphrase : SshKeyException("Wrong passphrase for the key")
}
