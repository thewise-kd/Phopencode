package io.github.thewisekd.phopencode.core.remote.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The file's format is tested with its parser in `state/ServerStateTest`. */
class StateFileLocationTest {
    @Test
    fun `location in the state folder must be a plain relative path`() {
        assertEquals("phopencode/server.state", StateFileLocation.DEFAULT.relativePath)
        listOf("", "/etc/passwd", "../x", "a/../../x", "a//b", "a/./b", "a/", "a\nb").forEach {
            assertFailsWith<IllegalArgumentException>(it) { StateFileLocation(it) }
        }
    }
}
