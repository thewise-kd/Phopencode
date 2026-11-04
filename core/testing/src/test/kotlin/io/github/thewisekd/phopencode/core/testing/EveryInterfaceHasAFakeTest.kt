package io.github.thewisekd.phopencode.core.testing

import java.io.File
import java.net.JarURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lists the service interfaces from the compiled API modules, so that an interface added later
 * without a fake fails here. A sealed interface is a closed set of values, not something to fake.
 */
class EveryInterfaceHasAFakeTest {
    private val base = "io.github.thewisekd.phopencode.core"

    private fun classNames(packageName: String): List<String> {
        val path = packageName.replace('.', '/')
        return javaClass.classLoader.getResources(path).toList().flatMap { url ->
            when (url.protocol) {
                "file" -> File(url.toURI()).list().orEmpty().toList()
                "jar" -> (url.openConnection() as JarURLConnection).jarFile.entries().toList()
                    .map { it.name }
                    .filter { it.startsWith("$path/") && '/' !in it.removePrefix("$path/") }
                    .map { it.removePrefix("$path/") }
                else -> emptyList()
            }
        }.filter { it.endsWith(".class") }.map { "$packageName.${it.removeSuffix(".class")}" }
    }

    private fun interfacesOf(packageName: String): List<Class<*>> = classNames(packageName)
        .map { Class.forName(it, false, javaClass.classLoader) }
        .filter { it.isInterface && !it.isSealed && !it.isAnnotation }

    private fun assertFaked(packageName: String, fakePackage: String, expected: Set<String>) {
        val interfaces = interfacesOf(packageName)
        assertEquals(expected, interfaces.map { it.simpleName }.toSet(), "The interfaces of $packageName")
        interfaces.forEach { service ->
            val fake = Class.forName("$base.testing.$fakePackage.Fake${service.simpleName}")
            assertTrue(service.isAssignableFrom(fake), "${fake.simpleName} must implement ${service.simpleName}")
        }
    }

    @Test
    fun `every interface of core api has a fake`() {
        assertFaked(
            "$base.api",
            "api",
            setOf(
                "SessionApi",
                "MessageApi",
                "InteractionApi",
                "CatalogApi",
                "VcsApi",
                "FsApi",
                "EventApi",
                "OpencodeClient",
            ),
        )
    }

    @Test
    fun `every interface of core ssh api has a fake`() {
        assertFaked(
            "$base.ssh.api",
            "ssh",
            setOf("SshConnector", "SshSession", "RemoteExec", "SftpClient", "HostKeyVerifier"),
        )
    }

    @Test
    fun `every interface of core storage api has a fake`() {
        assertFaked(
            "$base.storage_api",
            "storage",
            setOf(
                "SecretStore",
                "ProfileRepository",
                "KnownHostsStore",
                "SettingsRepository",
                "SshKeyIndex",
                "RecentFoldersStore",
            ),
        )
    }
}
