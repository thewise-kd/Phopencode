package io.github.thewisekd.phopencode.core.ssh.sftp.write

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The temporary, probe and backup names keep their form: prefix, 16 random bytes in lowercase hex, suffix. */
class TempNameTest {
    private val form = Regex("""\.phopencode-[0-9a-f]{32}\.(tmp|probe|bak)""")

    @Test
    fun nameIsPrefixThirtyTwoLowercaseHexDigitsAndSuffix() {
        for (suffix in listOf(".tmp", ".probe", ".bak")) {
            repeat(200) {
                val name = SftpWriter.randomName(suffix)
                assertTrue(form.matches(name), "bad form: $name")
                assertTrue(name.endsWith(suffix), "bad suffix: $name")
            }
        }
    }

    @Test
    fun namesDiffer() {
        assertNotEquals(SftpWriter.randomName(".tmp"), SftpWriter.randomName(".tmp"))
    }

    @Test
    fun hexIsLowercaseTwoDigitsPerByteInOrder() {
        val bytes = byteArrayOf(0, 1, 0x0f, 0x10, 0x7f, -128, -85, -1)
        assertEquals("00010f107f80abff", SftpWriter.hexOf(bytes))
        assertEquals("", SftpWriter.hexOf(ByteArray(0)))
    }

    @Test
    fun hexCoversEveryByteValue() {
        val bytes = ByteArray(256) { it.toByte() }
        val expected = (0..255).joinToString("") { it.toString(16).padStart(2, '0') }
        assertEquals(expected, SftpWriter.hexOf(bytes))
    }
}
