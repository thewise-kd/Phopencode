package io.github.thewisekd.phopencode.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The live preview's two routes: the port picker, and the page of one port on the server's
 * loopback. A port outside 1..65535 never becomes a route, neither in code nor from saved state.
 */
class PreviewRouteTest {
    private fun roundTrip(route: PreviewRoute.Page): PreviewRoute.Page =
        Json.decodeFromString(PreviewRoute.Page.serializer(), Json.encodeToString(PreviewRoute.Page.serializer(), route))

    @Test
    fun `the port picker takes no arguments`() {
        val serializer = PreviewRoute.Ports.serializer()

        assertEquals(0, serializer.descriptor.elementsCount)
        assertEquals(PreviewRoute.Ports, Json.decodeFromString(serializer, Json.encodeToString(serializer, PreviewRoute.Ports)))
    }

    @Test
    fun `the page keeps its port through a round trip, at both ends of the range`() {
        listOf(1, 80, 3000, 5173, 8080, 65535).forEach { port ->
            assertEquals(PreviewRoute.Page(port), roundTrip(PreviewRoute.Page(port)))
        }
    }

    @Test
    fun `the page has one required argument, the port`() {
        val descriptor = PreviewRoute.Page.serializer().descriptor

        assertEquals(1, descriptor.elementsCount)
        assertEquals("port", descriptor.getElementName(0))
        assertFalse(descriptor.isElementOptional(0))
    }

    @Test
    fun `a port outside 1 to 65535 is refused in code`() {
        listOf(Int.MIN_VALUE, -1, 0, 65536, Int.MAX_VALUE).forEach { port ->
            assertFailsWith<IllegalArgumentException>("port $port") { PreviewRoute.Page(port) }
        }
    }

    @Test
    fun `a port outside 1 to 65535 is refused from saved state`() {
        listOf("0", "-1", "65536").forEach { port ->
            assertFailsWith<IllegalArgumentException>("port $port") {
                Json.decodeFromString(PreviewRoute.Page.serializer(), """{"port":$port}""")
            }
        }
        assertFailsWith<SerializationException> { Json.decodeFromString(PreviewRoute.Page.serializer(), "{}") }
    }

    @Test
    fun `the message of a refused port does not repeat it`() {
        val failure = assertFailsWith<IllegalArgumentException> { PreviewRoute.Page(70000) }

        assertFalse(failure.message.orEmpty().contains("70000"))
    }

    @Test
    fun `pages of different ports are different routes`() {
        assertNotEquals<PreviewRoute>(PreviewRoute.Page(3000), PreviewRoute.Page(3001))
        assertNotEquals(PreviewRoute.Ports.toString(), PreviewRoute.Page(3000).toString())
    }
}
