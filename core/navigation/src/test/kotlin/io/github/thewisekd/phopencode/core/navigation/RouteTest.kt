package io.github.thewisekd.phopencode.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlinx.serialization.serializerOrNull

@OptIn(ExperimentalSerializationApi::class)
class RouteTest {
    /** Every class in the sealed hierarchy, so that a new route cannot go unseen here. */
    private val concreteRoutes: Set<Class<*>> = concreteClasses(Route::class.java)

    private fun concreteClasses(type: Class<*>): Set<Class<*>> =
        if (type.isSealed) type.permittedSubclasses.flatMap(::concreteClasses).toSet() else setOf(type)

    @Suppress("UNCHECKED_CAST")
    private fun serializerOf(type: Class<*>) = serializer(type) as KSerializer<Route>

    private val descriptors: List<SerialDescriptor>
        get() = concreteRoutes.map { serializerOf(it).descriptor }

    private fun SerialDescriptor.forEachArgument(block: (name: String, index: Int) -> Unit) =
        (0 until elementsCount).forEach { block(getElementName(it), it) }

    private fun assertRoundTrip(route: Route) {
        val serializer = serializerOf(route.javaClass)

        assertEquals(route, Json.decodeFromString(serializer, Json.encodeToString(serializer, route)))
    }

    @Test
    fun `there is one sealed group per feature`() {
        val features = Route::class.java.permittedSubclasses.map { it.simpleName }.toSet()

        assertEquals(
            setOf(
                "ConnectRoute",
                "SessionsRoute",
                "ChatRoute",
                "CatalogRoute",
                "FilesRoute",
                "GitRoute",
                "NotifyRoute",
                "SettingsRoute",
            ),
            features,
        )
    }

    @Test
    fun `every concrete route has a sample`() {
        assertEquals(
            concreteRoutes.map { it.name }.toSortedSet(),
            routesWith("sample").map { it.javaClass.name }.toSortedSet(),
        )
    }

    @Test
    fun `every concrete route is Serializable`() {
        concreteRoutes.forEach { type ->
            assertFalse(type.isInterface, "${type.name} is an unsealed group")
            assertTrue(type.isAnnotationPresent(Serializable::class.java), "${type.name} is not @Serializable")
            assertNotNull(serializerOrNull(type), "${type.name} has no serializer")
        }
    }

    @Test
    fun `every route survives a round trip`() {
        (routesWith("sample") + routesWithDefaults).forEach(::assertRoundTrip)
    }

    @Test
    fun `awkward arguments survive a round trip in every route`() {
        listOf(
            "/home/user/my project/src/Main.kt",
            "a/b/c",
            "/../..",
            "  spaces  ",
            "/srv/Türkçe klasör/ığüşöçİIi.txt",
            "/srv/100%/50%25.txt",
            "what?a=b&c=d#fragment+plus",
            "quote\"'\\back`\$(rm)",
            "{braces}",
            "line\nbreak\ttab",
            "null",
            "\uD83D\uDE00.txt",
        ).forEach { text -> routesWith(text).forEach(::assertRoundTrip) }
    }

    @Test
    fun `route names are unique, because navigation matches a destination by them`() {
        val names = descriptors.map { it.serialName }

        assertEquals(names.size, names.toSet().size, names.toString())
    }

    @Test
    fun `arguments are types navigation handles without a custom NavType`() {
        val handled = setOf(PrimitiveKind.STRING, PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.BOOLEAN)

        descriptors.forEach { route ->
            route.forEachArgument { name, index ->
                val argument = route.getElementDescriptor(index)
                val label = "${route.serialName}.$name"

                assertFalse(argument.isInline, "$label is a value class")
                assertTrue(argument.kind in handled, "$label is ${argument.kind}")
            }
        }
    }

    @Test
    fun `a nullable argument has a default, or navigation makes it a path segment`() {
        descriptors.forEach { route ->
            route.forEachArgument { name, index ->
                if (route.getElementDescriptor(index).isNullable) {
                    assertTrue(route.isElementOptional(index), "${route.serialName}.$name has no default")
                }
            }
        }
    }

    @Test
    fun `a nullable string argument never holds a free-form name`() {
        // Navigation reads the text "null" in a nullable string as null, so a file or branch with
        // that name would be lost. Non-null strings do not have this problem.
        val neverLiterallyNull = setOf("serverId", "directory", "skillId")

        descriptors.forEach { route ->
            route.forEachArgument { name, index ->
                val argument = route.getElementDescriptor(index)
                if (argument.isNullable && argument.kind == PrimitiveKind.STRING) {
                    assertTrue(name in neverLiterallyNull, "${route.serialName}.$name")
                }
            }
        }
    }

    @Test
    fun `no argument is named after a secret or file contents`() {
        val forbidden = listOf(
            "password",
            "passphrase",
            "secret",
            "credential",
            "privatekey",
            "token",
            "topic",
            "content",
            "body",
        )

        descriptors.forEach { route ->
            route.forEachArgument { name, _ ->
                assertFalse(forbidden.any { it in name.lowercase() }, "${route.serialName}.$name")
            }
        }
    }

    @Test
    fun `the app starts on a route that needs no arguments`() {
        assertEquals(0, serializerOf(ConnectRoute.ServerList.javaClass).descriptor.elementsCount)
    }
}
