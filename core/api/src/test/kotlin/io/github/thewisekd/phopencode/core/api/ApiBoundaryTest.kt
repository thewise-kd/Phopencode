package io.github.thewisekd.phopencode.core.api

import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SessionRevert
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/** `core/model` applies the serialization plugin, so JSON types do compile here. Only this stops them. */
private val FORBIDDEN_PREFIXES = listOf(
    "okhttp3.",
    "okio.",
    "retrofit2.",
    "io.ktor.",
    "java.net.http.",
    "javax.net.",
    "java.net.HttpURLConnection",
    "java.net.URL",
    "java.net.URI",
    "java.net.Socket",
    "kotlinx.serialization.",
    "org.json.",
    "com.google.gson.",
    "com.squareup.moshi.",
    "com.fasterxml.jackson.",
)

private val API_INTERFACES: List<Class<*>> = listOf(
    SessionApi::class.java,
    MessageApi::class.java,
    InteractionApi::class.java,
    CatalogApi::class.java,
    VcsApi::class.java,
    FsApi::class.java,
    EventApi::class.java,
)

private interface LeakyReturn {
    suspend fun read(): JsonElement
}

private interface LeakyParameter {
    fun read(values: List<Map<String, HttpURLConnection>>)
}

private class LeakyField(val body: Array<JsonElement>)

private abstract class LeakySuper : Comparable<JsonElement>

class ApiBoundaryTest {
    private val mainClasses: Path =
        Path.of(OpencodeClient::class.java.protectionDomain.codeSource.location.toURI())

    private fun compiledClasses(): List<Class<*>> = Files.walk(mainClasses).use { paths ->
        paths.filter { it.extension == "class" }.toList()
    }.map { file ->
        val name = file.relativeTo(mainClasses).invariantSeparatorsPathString
            .removeSuffix(".class")
            .replace('/', '.')
        Class.forName(name, false, OpencodeClient::class.java.classLoader)
    }

    private fun namesIn(type: Type, seen: MutableSet<Type> = mutableSetOf()): Set<String> {
        if (!seen.add(type)) return emptySet()
        return when (type) {
            is Class<*> -> if (type.isArray) namesIn(type.componentType, seen) else setOf(type.name)
            is ParameterizedType ->
                (type.actualTypeArguments.toList() + type.rawType).flatMap { namesIn(it, seen) }.toSet()
            is WildcardType -> (type.upperBounds + type.lowerBounds).flatMap { namesIn(it, seen) }.toSet()
            is GenericArrayType -> namesIn(type.genericComponentType, seen)
            is TypeVariable<*> -> type.bounds.flatMap { namesIn(it, seen) }.toSet()
            else -> emptySet()
        }
    }

    /** Every type a class names in its declaration, including the result inside a suspend function's `Continuation`. */
    private fun signatureTypes(type: Class<*>): Set<String> {
        val declared = buildList<Type> {
            type.genericSuperclass?.let(::add)
            addAll(type.genericInterfaces)
            type.declaredFields.forEach { add(it.genericType) }
            type.declaredConstructors.forEach { addAll(it.genericParameterTypes) }
            type.declaredMethods.forEach { method ->
                add(method.genericReturnType)
                addAll(method.genericParameterTypes)
            }
        }
        return declared.flatMap { namesIn(it) }.toSet()
    }

    private fun forbiddenIn(type: Class<*>): Set<String> =
        signatureTypes(type).filter { name -> FORBIDDEN_PREFIXES.any(name::startsWith) }.toSet()

    @Test
    fun `the check sees a type inside a suspend result, a generic, an array and a supertype`() {
        assertEquals(setOf(JsonElement::class.java.name), forbiddenIn(LeakyReturn::class.java))
        assertEquals(setOf("java.net.HttpURLConnection"), forbiddenIn(LeakyParameter::class.java))
        assertEquals(setOf(JsonElement::class.java.name), forbiddenIn(LeakyField::class.java))
        assertEquals(setOf(JsonElement::class.java.name), forbiddenIn(LeakySuper::class.java))
    }

    @Test
    fun `the check covers the whole module`() {
        val scanned = compiledClasses()

        assertTrue(scanned.containsAll(API_INTERFACES + OpencodeClient::class.java + ServerEvent::class.java))
    }

    @Test
    fun `no signature in this module uses an HTTP or JSON type`() {
        val leaks = compiledClasses().associate { it.name to forbiddenIn(it) }.filterValues { it.isNotEmpty() }

        assertEquals(emptyMap(), leaks)
    }

    @Test
    fun `each API interface lives in a file of its own`() {
        val sources = Path.of("src/main/kotlin/io/github/thewisekd/phopencode/core/api")

        (API_INTERFACES + OpencodeClient::class.java).forEach { type ->
            val file = sources.resolve("${type.simpleName}.kt")
            assertTrue(file.exists(), "missing $file")
            assertTrue("interface ${type.simpleName}" in file.readText(), "not declared in $file")
        }
    }

    @Test
    fun `calls suspend, and only the event stream is a flow`() {
        val methods = API_INTERFACES.flatMap { type ->
            type.declaredMethods.filter { Modifier.isAbstract(it.modifiers) }
        }
        val (streams, calls) = methods.partition { it.declaringClass == EventApi::class.java }

        assertTrue(calls.isNotEmpty())
        calls.forEach { method ->
            assertEquals(Continuation::class.java, method.parameterTypes.lastOrNull(), "not suspend: $method")
        }
        assertEquals(listOf(Flow::class.java), streams.map { it.returnType })
        assertFalse(streams.single().parameterTypes.contains(Continuation::class.java))
    }

    private fun operations(type: Class<*>): Set<String> = type.declaredMethods
        .filter { Modifier.isAbstract(it.modifiers) }
        .map { it.name.substringBefore('-') }
        .toSet()

    @Test
    fun `the interfaces cover what the P2 tasks implement`() {
        assertEquals(
            setOf("create", "list", "get", "delete", "update", "stop", "fork", "compact", "activeStatuses"),
            operations(SessionApi::class.java),
        )
        assertEquals(
            setOf(
                "prompt",
                "list",
                "inbox",
                "command",
                "switchAgent",
                "switchModel",
                "turnDiff",
                "stageRevert",
                "commitRevert",
                "cancelRevert",
            ),
            operations(MessageApi::class.java),
        )
        assertEquals(
            setOf("listForms", "replyForm", "cancelForm", "listPermissions", "replyPermission"),
            operations(InteractionApi::class.java),
        )
        assertEquals(
            setOf("skills", "commands", "agents", "models", "defaultModel", "providers", "mcpServers", "plugins"),
            operations(CatalogApi::class.java),
        )
        assertEquals(setOf("current", "status", "diff", "branches"), operations(VcsApi::class.java))
        assertEquals(setOf("find"), operations(FsApi::class.java))
        assertEquals(setOf("events"), operations(EventApi::class.java))
    }

    @Test
    fun `a client can be closed, and closing throws no checked exception`() {
        assertTrue(AutoCloseable::class.java.isAssignableFrom(OpencodeClient::class.java))
        assertEquals(emptyList(), OpencodeClient::class.java.getMethod("close").exceptionTypes.toList())
    }

    @Test
    fun `every diff mode is available`() {
        assertEquals(
            setOf(VcsDiffMode.Working, VcsDiffMode.Branch, VcsDiffMode.Committed),
            VcsDiffMode.entries.toSet(),
        )
    }

    @Test
    fun `a prompt can steer or queue`() {
        assertEquals(setOf(PromptDelivery.Steer, PromptDelivery.Queue), PromptDelivery.entries.toSet())
        assertEquals(PromptDelivery.Steer, Prompt("text", delivery = PromptDelivery.Steer).delivery)
    }

    @Test
    fun `a session list can filter on children`() {
        val parent = SessionId("ses_1")

        assertEquals(
            SessionParentFilter.ChildrenOf(parent),
            SessionQuery(parent = SessionParentFilter.ChildrenOf(parent)).parent,
        )
        assertEquals(SessionParentFilter.Any, SessionQuery().parent)
    }

    @Test
    fun `a page keeps both cursors of the server's cursor object`() {
        val page = Page(listOf(1), previousCursor = PageCursor("back"), nextCursor = PageCursor("next"))

        assertEquals(PageCursor("back"), page.previousCursor)
        assertEquals(PageCursor("next"), page.nextCursor)
    }

    @Test
    fun `a next cursor alone does not mean more follows, because the last page has one too`() {
        assertFalse(Page(listOf(1), nextCursor = PageCursor("next")).isEnd)
        assertTrue(Page(emptyList<Int>(), nextCursor = PageCursor("next")).isEnd)
        assertTrue(Page(listOf(1)).isEnd)
    }

    /** A suspend function's result, which sits in the type of its last parameter. */
    private fun resultOf(name: String, type: Class<*>): String = type.declaredMethods
        .filter { Modifier.isAbstract(it.modifiers) }
        .single { it.name.substringBefore('-') == name }
        .genericParameterTypes.last().typeName

    @Test
    fun `prompt and compact return the inbox item the server made`() {
        assertTrue(InboxItem::class.java.name in resultOf("prompt", MessageApi::class.java))
        assertTrue(InboxItem::class.java.name in resultOf("compact", SessionApi::class.java))
    }

    @Test
    fun `stop says whether anything was interrupted, and staging returns the revert`() {
        assertTrue(Boolean::class.javaObjectType.name in resultOf("stop", SessionApi::class.java))
        assertTrue(SessionRevert::class.java.name in resultOf("stageRevert", MessageApi::class.java))
    }

    @Test
    fun `active statuses can only be running or something unknown`() {
        assertEquals(
            setOf("Running", "Unknown"),
            SessionActivity::class.java.permittedSubclasses.map { it.simpleName }.toSet(),
        )
        assertTrue(SessionActivity::class.java.name in resultOf("activeStatuses", SessionApi::class.java))
    }

    @Test
    fun `an inbox item holds the text of a prompt under its own kind`() {
        val item = InboxItem(
            MessageId("msg_1"),
            SessionId("ses_1"),
            content = InboxContent.User("Begin."),
            delivery = PromptDelivery.Steer,
        )

        assertEquals("Begin.", (item.content as InboxContent.User).text)
        assertEquals(InboxContent.Unknown("move"), item.copy(content = InboxContent.Unknown("move")).content)
    }

    @Test
    fun `a VCS branch is a plain name`() {
        assertEquals(setOf("name"), VcsBranch::class.java.declaredFields.map { it.name }.toSet())
        assertEquals("main", VcsBranch("main").name)
    }

    @Test
    fun `an MCP status can be pending`() {
        assertEquals(McpStatus.Pending, McpServer("tools", McpStatus.Pending).status)
    }

    @Test
    fun `a plugin has an id and a status, and no name or version`() {
        val names = Plugin::class.java.declaredFields.map { it.name }.toSet()

        assertEquals(setOf("id", "status", "error"), names)
        assertEquals(PluginStatus.Active, Plugin("opencode.agent", PluginStatus.Active).status)
    }

    @Test
    fun `a plugin does not print its error text`() {
        val plugin = Plugin(id = null, status = PluginStatus.Failed, error = "SENTINEL-plugin-error")

        assertFalse("SENTINEL" in plugin.toString())
        assertEquals("SENTINEL-plugin-error", plugin.error)
    }

    @Test
    fun `an MCP server does not print its error text`() {
        val server = McpServer("tools", McpStatus.Failed, error = "SENTINEL-mcp-error")

        assertFalse("SENTINEL" in server.toString())
        assertEquals("SENTINEL-mcp-error", server.error)
    }
}
