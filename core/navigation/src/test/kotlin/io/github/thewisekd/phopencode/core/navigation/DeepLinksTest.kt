package io.github.thewisekd.phopencode.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.serializer

class DeepLinksTest {
    @Test
    fun `the pending link has the exact agreed value`() {
        assertEquals("phopencode://pending", DeepLinks.PENDING)
    }

    @Test
    fun `the pending route needs nothing the link does not carry`() {
        assertEquals(0, serializer<NotifyRoute.Pending>().descriptor.elementsCount)
    }
}
