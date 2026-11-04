package io.github.thewisekd.phopencode.security

import java.security.Provider
import java.security.Security

/**
 * Puts a provider of a given class under a JCA provider name, in the place the old one held.
 *
 * Keeping the place matters: a lookup that names the provider (`getInstance(alg, "BC")`) gets the
 * new one, while a lookup without a name still walks the providers in the platform's order, so
 * nothing that worked before (Conscrypt, the Android Keystore) is shadowed by the newcomer.
 *
 * Idempotent and thread-safe: calls are serialised, and a provider that is already of the wanted
 * class is kept as it is. Code outside this object that changes the table at the same moment can
 * still win the name; that is reported, not papered over.
 */
object ProviderReplacement {
    private val lock = Any()

    /**
     * @param create Called at most once per call, and only when a replacement is needed. The
     *   provider it returns must be named [name].
     * @return The provider registered under [name] afterwards, of class [type].
     * @throws IllegalArgumentException when [create] returns a provider of another name or class.
     * @throws IllegalStateException when something outside this object took the name meanwhile.
     */
    fun <T : Provider> replace(name: String, type: Class<T>, create: () -> T): T = synchronized(lock) {
        Security.getProvider(name)?.let { current -> if (type.isInstance(current)) return type.cast(current)!! }

        val replacement = create()
        require(replacement.name == name) { "The replacement is not named $name" }
        require(type.isInstance(replacement)) { "The replacement is not a ${type.name}" }

        // getProviders is ordered by preference; insertProviderAt counts from 1.
        val position = Security.getProviders().indexOfFirst { it.name == name }
        if (position >= 0) Security.removeProvider(name)
        val inserted = if (position >= 0) {
            Security.insertProviderAt(replacement, position + 1)
        } else {
            Security.addProvider(replacement)
        }
        if (inserted == NOT_INSERTED) {
            val winner = Security.getProvider(name)
            check(type.isInstance(winner)) { "Another provider took the name $name" }
            return type.cast(winner)!!
        }
        replacement
    }

    /** What `insertProviderAt` and `addProvider` return when the name is already taken. */
    private const val NOT_INSERTED = -1
}
