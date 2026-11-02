package io.github.thewisekd.phopencode.core.ssh.api

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.schmizz.sshj.SSHClient
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * JSch must be able to replace sshj behind this package, so nothing here may name an SSH library.
 * It reads compiled classes rather than sources: a constant pool lists every type a class touches,
 * including fully qualified uses, generics and method bodies, and comments are not in it.
 */
class ApiDependencyTest {
    private val libraries = listOf(
        "net.schmizz",
        "com.hierynomus",
        "org.bouncycastle",
        "com.jcraft",
        "org.apache.sshd",
    )

    private val apiPackage = SshConnector::class.java.packageName

    private fun apiClassFiles(): List<Path> {
        val classes = SshConnector::class.java.protectionDomain.codeSource.location.toURI().toPath()
        val directory = classes.resolve(apiPackage.replace('.', '/'))
        assertTrue(Files.isDirectory(directory), "compiled classes not found")
        return Files.walk(directory).use { paths ->
            paths.filter { it.extension == "class" }.toList()
        }
    }

    private fun librariesNamedIn(classFile: ByteArray): List<String> {
        val text = String(classFile, Charsets.ISO_8859_1)
        return libraries.filter { it in text || it.replace('.', '/') in text }
    }

    private fun bytesOf(type: Class<*>): ByteArray {
        val resource = type.name.replace('.', '/') + ".class"
        return checkNotNull(type.classLoader.getResourceAsStream(resource)).use { it.readBytes() }
    }

    @Suppress("unused")
    private class HoldsSshj(val client: SSHClient?)

    private class UsesBouncyCastleInABody {
        fun name(): String = BouncyCastleProvider().name
    }

    @Test
    fun `the check sees a library type in a signature`() {
        assertEquals(listOf("net.schmizz"), librariesNamedIn(bytesOf(HoldsSshj::class.java)))
    }

    @Test
    fun `the check sees a library type used only in a method body`() {
        assertEquals(listOf("org.bouncycastle"), librariesNamedIn(bytesOf(UsesBouncyCastleInABody::class.java)))
    }

    @Test
    fun `the check covers the four interfaces`() {
        val names = apiClassFiles().map { it.name }
        listOf("SshConnector", "RemoteExec", "SftpClient", "HostKeyVerifier").forEach { name ->
            assertTrue("$name.class" in names, "$name is not in the api package")
        }
    }

    @Test
    fun `no class in the api package names an SSH library`() {
        val offenders = apiClassFiles().filter { librariesNamedIn(it.readBytes()).isNotEmpty() }
        assertEquals(emptyList(), offenders.map { it.name })
    }
}
