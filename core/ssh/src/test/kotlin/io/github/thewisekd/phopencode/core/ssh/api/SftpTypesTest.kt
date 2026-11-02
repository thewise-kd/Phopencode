package io.github.thewisekd.phopencode.core.ssh.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SftpTypesTest {
    @Test
    fun `by default a write replaces nothing and sets no mode`() {
        val options = SftpWriteOptions()
        assertFalse(options.overwrite)
        assertNull(options.permissions)
    }

    @Test
    fun `owner only is mode 0600`() {
        assertEquals("600", SftpWriteOptions.OWNER_ONLY.toString(radix = 8))
    }

    @Test
    fun `permissions outside the mode bits are rejected`() {
        assertFailsWith<IllegalArgumentException> { SftpWriteOptions(permissions = -1) }
        assertFailsWith<IllegalArgumentException> { SftpWriteOptions(permissions = 4096) }
        assertEquals(4095, SftpWriteOptions(permissions = 4095).permissions)
    }
}
