package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.Skill
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compared with names reduced to lower-case letters and digits, so `api_key` and `apiKey` both match. */
private val FORBIDDEN_WORDS = listOf("apikey", "setting", "header")

private const val OWN_PACKAGE = "io.github.thewisekd.phopencode.core"

/** What `/api/provider`, `/api/model` and `/api/config` return in clear, and the routes the catalog reads. */
private val CATALOG_TYPES: List<Class<*>> = listOf(
    Model::class.java,
    Provider::class.java,
    Agent::class.java,
    Plugin::class.java,
    McpServer::class.java,
    Skill::class.java,
    Command::class.java,
)

private class FieldHolder(val settings: Map<String, String>)

private class LeakyName(val api_key: String)

private interface LeakyGetter {
    val requestHeaders: Map<String, String>
}

private class ProviderSettings

private class LeakyCatalogEntry(val extra: Map<String, DataValue>)

private class LeakyNested(val entry: List<LeakyNestedValue>)

private class LeakyNestedValue(val value: DataValue?)

private class CleanEntry(val name: String, val nested: List<CleanValue>)

private class CleanValue(val size: Long)

/**
 * The server returns `settings.apiKey` in clear, so decoding must drop `settings` and `headers`. These
 * tests hold that by making the types unable to carry them, in both this module and `core/model`.
 */
class NoServerSecretsTest {
    /** A module on the test classpath is a folder of classes or a jar, depending on the module. */
    private fun classFilesOf(root: Path): List<String> = if (Files.isDirectory(root)) {
        Files.walk(root).use { paths ->
            paths.filter { it.extension == "class" }.toList()
        }.map { it.relativeTo(root).invariantSeparatorsPathString }
    } else {
        ZipFile(root.toFile()).use { jar -> jar.entries().asSequence().map { it.name }.toList() }
            .filter { it.endsWith(".class") }
    }

    private fun classesOf(module: Class<*>): List<Class<*>> =
        classFilesOf(Path.of(module.protectionDomain.codeSource.location.toURI()))
            .filterNot { it.startsWith("META-INF/") || it.endsWith("module-info.class") }
            .map { Class.forName(it.removeSuffix(".class").replace('/', '.'), false, module.classLoader) }

    private fun contractClasses(): List<Class<*>> =
        listOf(OpencodeClient::class.java, Session::class.java).flatMap(::classesOf)

    private fun normalized(name: String): String = name.lowercase().filter(Char::isLetterOrDigit)

    private fun forbiddenNamesIn(type: Class<*>): Set<String> {
        val names = buildList {
            add(type.simpleName)
            type.declaredFields.forEach { add(it.name) }
            type.declaredMethods.forEach { add(it.name) }
        }
        return names.filter { name -> FORBIDDEN_WORDS.any { it in normalized(name) } }.toSet()
    }

    private fun classesNamedBy(type: Type): Set<Class<*>> = when (type) {
        is Class<*> -> if (type.isArray) classesNamedBy(type.componentType) else setOf(type)
        is ParameterizedType -> (type.actualTypeArguments.toList() + type.rawType).flatMap(::classesNamedBy).toSet()
        is WildcardType -> (type.upperBounds + type.lowerBounds).flatMap(::classesNamedBy).toSet()
        is GenericArrayType -> classesNamedBy(type.genericComponentType)
        else -> emptySet()
    }

    /** All classes a type's fields can hold, following nested types and sealed cases. */
    private fun reachableFrom(start: Class<*>): Set<Class<*>> {
        val seen = mutableSetOf<Class<*>>()
        val pending = ArrayDeque(listOf(start))
        while (pending.isNotEmpty()) {
            val type = pending.removeFirst()
            if (!seen.add(type)) continue
            if (!type.name.startsWith(OWN_PACKAGE)) continue
            pending += type.declaredFields.flatMap { classesNamedBy(it.genericType) }
            pending += type.permittedSubclasses.orEmpty()
        }
        return seen
    }

    /** A free-form value or a map could hold anything the server sent, secrets included. */
    private fun freeFormIn(type: Class<*>): Set<Class<*>> = reachableFrom(type).filter {
        it == DataValue::class.java || Map::class.java.isAssignableFrom(it) || it == Any::class.java
    }.toSet()

    @Test
    fun `the name check sees a field, a snake case name, a getter and a class name`() {
        assertTrue("settings" in forbiddenNamesIn(FieldHolder::class.java))
        assertTrue("api_key" in forbiddenNamesIn(LeakyName::class.java))
        assertEquals(setOf("getRequestHeaders"), forbiddenNamesIn(LeakyGetter::class.java))
        assertEquals(setOf("ProviderSettings"), forbiddenNamesIn(ProviderSettings::class.java))
    }

    @Test
    fun `the free form check sees a map, and a value behind a nested type`() {
        assertTrue(freeFormIn(LeakyCatalogEntry::class.java).any { Map::class.java.isAssignableFrom(it) })
        assertTrue(DataValue::class.java in freeFormIn(LeakyNested::class.java))
        assertEquals(emptySet(), freeFormIn(CleanEntry::class.java))
    }

    @Test
    fun `the scan covers both modules`() {
        val scanned = contractClasses()

        assertTrue(scanned.containsAll(CATALOG_TYPES))
        assertTrue(scanned.containsAll(listOf(ServerEvent::class.java, Session::class.java, DataValue::class.java)))
    }

    @Test
    fun `no model or API type names settings, headers or an API key`() {
        val leaks = contractClasses().associate { it.name to forbiddenNamesIn(it) }.filterValues { it.isNotEmpty() }

        assertEquals(emptyMap(), leaks)
    }

    @Test
    fun `no catalog type has a free form field that could hold them under another name`() {
        val leaks = CATALOG_TYPES.associate { it.name to freeFormIn(it) }.filterValues { it.isNotEmpty() }

        assertEquals(emptyMap(), leaks)
    }
}
