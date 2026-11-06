package io.github.thewisekd.phopencode.feature.connect.logic.folder

import io.github.thewisekd.phopencode.core.remote.connection.NotConnectedException
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FailureDetailTest {
    @Test
    fun `an SFTP failure names its failure, and a library cause its full class name`() {
        val failure = SshException.Sftp(SftpFailure.Failed, SocketException("reset"))

        assertEquals("SshException.Sftp(Failed) <- java.net.SocketException", FailureDetail.of(failure))
    }

    @Test
    fun `a JDK exception keeps its full class name`() {
        assertEquals("java.lang.IllegalStateException", FailureDetail.of(IllegalStateException("broken")))
    }

    @Test
    fun `a project class is named simply, with its outer class`() {
        assertEquals(
            "NotConnectedException <- SshException.Disconnected",
            FailureDetail.of(NotConnectedException().apply { initCause(SshException.Disconnected()) }),
        )
    }

    @Test
    fun `a redacted failure is named after the class it stands in for`() {
        val failure = SshException.Sftp(SftpFailure.Failed, redacted(EOFException("closed by $SECRET_HOST")))

        assertEquals("SshException.Sftp(Failed) <- java.io.EOFException", FailureDetail.of(failure))
    }

    @Test
    fun `a redacted chain names every class it stands in for`() {
        val original = IOException("open $SECRET_PATH", IllegalStateException("as $SECRET_USER"))
        val failure = SshException.Sftp(SftpFailure.Failed, redacted(original))

        assertEquals(
            "SshException.Sftp(Failed) <- java.io.IOException <- java.lang.IllegalStateException",
            FailureDetail.of(failure),
        )
    }

    @Test
    fun `a redacted failure whose message is not a class name is not quoted`() {
        val failure = LookAlike("$SECRET_HOST $SECRET_PASSWORD")

        assertEquals("RedactedFailure", FailureDetail.of(failure, redactedClass = LookAlike::class.java.name))
    }

    @Test
    fun `the chain stops after five links`() {
        var failure: Throwable = EOFException()
        repeat(7) { failure = IOException(failure) }

        val detail = FailureDetail.of(failure)

        assertEquals(List(5) { "java.io.IOException" }.joinToString(" <- "), detail)
    }

    @Test
    fun `a cause loop ends at five links`() {
        val first = IOException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)

        assertEquals(5, FailureDetail.of(first).split(" <- ").size)
    }

    @Test
    fun `no message text reaches the detail`() {
        val failure = SshException.Sftp(
            SftpFailure.Failed,
            IllegalStateException(
                "listing $SECRET_PATH on $SECRET_HOST",
                redacted(IOException("$SECRET_USER@$SECRET_HOST password $SECRET_PASSWORD")),
            ),
        )

        val detail = FailureDetail.of(failure)

        assertEquals(
            "SshException.Sftp(Failed) <- java.lang.IllegalStateException <- java.io.IOException",
            detail,
        )
        for (secret in SECRETS) assertFalse(secret in detail, "the detail quotes $secret")
    }

    /** Stands in for the redacted wrapper, with a message that is no class name. */
    private class LookAlike(message: String) : Exception(message)
}

/** Made-up sensitive text, assembled at runtime so that no literal looks like a real secret. */
internal val SECRET_PATH = "/home/" + "alice/" + "private-project"
internal val SECRET_HOST = "build-" + "box.internal"
internal val SECRET_USER = "alice" + "-admin"
internal val SECRET_PASSWORD = "pass-" + "word-" + "1234"
internal val SECRETS = listOf(SECRET_PATH, SECRET_HOST, SECRET_USER, SECRET_PASSWORD)

/**
 * The real redacted wrapper of core/ssh around [failure]. It is internal to that module, so it is
 * reached by reflection, through the same factory the SFTP client uses.
 */
internal fun redacted(failure: Throwable): Throwable {
    val type = Class.forName("io.github.thewisekd.phopencode.core.ssh.connect.RedactedFailure")
    val companion = type.getField("Companion").get(null)
    return companion.javaClass.getMethod("of", Throwable::class.java).invoke(companion, failure) as Throwable
}
