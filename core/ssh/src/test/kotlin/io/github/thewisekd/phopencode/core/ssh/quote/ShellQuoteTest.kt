package io.github.thewisekd.phopencode.core.ssh.quote

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

/** Everything here that claims a shell behaviour runs a real shell; nothing is parsed by hand. */
class ShellQuoteTest {
    @TempDir
    lateinit var dir: Path

    private val shells = listOf("sh", "bash")

    private val cases = mapOf(
        "plain word" to "plain",
        "space" to "two words",
        "leading and trailing spaces" to "  padded  ",
        "tab" to "a\tb",
        "newline" to "a\nb",
        "trailing newlines" to "a\n\n",
        "only a newline" to "\n",
        "carriage return" to "a\r\nb",
        "single quote" to "it's",
        "only a single quote" to "'",
        "repeated single quotes" to "''a'''",
        "double quote" to "say \"hi\"",
        "command substitution" to "\$(touch pwned).txt",
        "backticks" to "`touch pwned`",
        "variable" to "\$HOME and \${HOME}",
        "quote that tries to break out" to "'; touch pwned; '",
        "newline that tries to start a command" to "x\ntouch pwned\n",
        "separators" to "a; touch pwned && touch pwned | touch pwned & #",
        "redirection" to "> pwned",
        "leading dash" to "-rf",
        "leading double dash" to "--force",
        "only a dash" to "-",
        "empty string" to "",
        "star glob" to "*",
        "other globs" to "?lpha.txt [ab]*.txt {a,b}",
        "tilde" to "~",
        "backslash" to "a\\b",
        "trailing backslash" to "a\\",
        "backslash before a quote" to "\\'",
        "backslash n stays two characters" to "\\n",
        "bang" to "wow!",
        "history expansion" to "!! !\$",
        "turkish" to "çalışma klasörü/ĞÜŞİÖÇ ığüşöç.txt",
        "outside the basic plane" to "😀",
        "assignment" to "A=b",
        "percent" to "%s %d",
    )

    private fun shell(shell: String, command: String): ByteArray {
        val process = ProcessBuilder(shell, "-c", command)
            .directory(dir.toFile())
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .start()
        val stdout = process.inputStream.readBytes()
        val stderr = process.errorStream.readBytes().decodeToString()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "$shell did not finish")
        assertEquals(0, process.exitValue(), "$shell failed: $stderr")
        return stdout
    }

    /** What a separate script sees as `"$@"`, as raw bytes, one list entry per word. */
    private fun received(shell: String, arguments: List<String>): List<ByteArray> {
        val receiver = dir.resolve("receiver.sh").toFile()
        receiver.writeText("for argument in \"\$@\"; do printf '%s\\0' \"\$argument\"; done\n")
        val bytes = shell(shell, ShellQuote.join(listOf("sh", receiver.path) + arguments))
        val words = mutableListOf<ByteArray>()
        var start = 0
        bytes.forEachIndexed { index, byte ->
            if (byte == 0.toByte()) {
                words += bytes.copyOfRange(start, index)
                start = index + 1
            }
        }
        assertEquals(bytes.size, start, "output did not end with a separator")
        return words
    }

    private fun assertArrives(shell: String, values: List<String>) {
        dir.resolve("alpha.txt").toFile().writeText("glob target")
        val words = received(shell, values)
        assertEquals(values.size, words.size, "word count")
        values.zip(words).forEach { (value, word) ->
            assertContentEquals(value.encodeToByteArray(throwOnInvalidSequence = true), word)
        }
        assertFalse(dir.resolve("pwned").exists(), "the argument ran as a command")
    }

    @TestFactory
    fun `every value arrives as one word, byte for byte`(): List<DynamicTest> =
        shells.flatMap { shell ->
            cases.map { (name, value) ->
                dynamicTest("$shell: $name") { assertArrives(shell, listOf(value)) }
            }
        }

    @TestFactory
    fun `values stay separate when joined into one command`(): List<DynamicTest> =
        shells.map { shell -> dynamicTest(shell) { assertArrives(shell, cases.values.toList()) } }

    @TestFactory
    fun `random hostile values arrive unchanged`(): List<DynamicTest> {
        val alphabet = "'\"\\\$`!*?[]{}()<>|&;#~=% \t\n\r-aZ0çğış".map { it.toString() } + "😀"
        val random = Random(20261102)
        val values = List(500) {
            buildString { repeat(random.nextInt(12)) { append(alphabet.random(random)) } }
        }
        return shells.map { shell -> dynamicTest(shell) { assertArrives(shell, values) } }
    }

    @Test
    fun `quoting wraps the whole value in single quotes`() {
        assertEquals("'a b'", ShellQuote.quote("a b"))
        assertEquals("''", ShellQuote.quote(""))
        assertEquals("'-rf'", ShellQuote.quote("-rf"))
        assertEquals("'it'\\''s'", ShellQuote.quote("it's"))
    }

    @Test
    fun `join separates words with one space`() {
        assertEquals("'git' 'add' '--' '-rf'", ShellQuote.join(listOf("git", "add", "--", "-rf")))
        assertEquals("", ShellQuote.join(emptyList()))
    }

    @Test
    fun `a leading dash still needs a separator at the call site`() {
        dir.resolve("-n").writeText("file content\n")

        // The quoted word reaches cat intact, and cat still takes it for an option.
        assertEquals("", shell("sh", ShellQuote.join(listOf("cat", "-n"))).decodeToString())
        // With the separator it names the file.
        assertEquals("file content\n", shell("sh", ShellQuote.join(listOf("cat", "--", "-n"))).decodeToString())
    }

    @Test
    fun `a name that looks like an option is deleted, not parsed, behind the separator`() {
        val target = dir.resolve("-rf").createFile()
        shell("sh", ShellQuote.join(listOf("rm", "--", "-rf")))
        assertFalse(target.exists())
    }

    @Test
    fun `a NUL byte is rejected, because no shell word can hold one`() {
        listOf("\u0000", "a\u0000", "\u0000a", "sentinel-value\u0000b").forEach { value ->
            val failure = assertFailsWith<IllegalArgumentException> { ShellQuote.quote(value) }
            assertFalse("sentinel" in failure.message.orEmpty(), "the value entered the message")
        }
    }

    @Test
    fun `half a surrogate pair is rejected, because it has no UTF-8 bytes`() {
        listOf("\uD83D", "\uDE00", "a\uD83Db", "\uDE00\uD83D", "sentinel-value\uD83D").forEach { value ->
            val failure = assertFailsWith<IllegalArgumentException> { ShellQuote.quote(value) }
            assertFalse("sentinel" in failure.message.orEmpty(), "the value entered the message")
        }
    }

    @Test
    fun `join rejects the same values as quote`() {
        assertFailsWith<IllegalArgumentException> { ShellQuote.join(listOf("ok", "a\u0000")) }
        assertFailsWith<IllegalArgumentException> { ShellQuote.join(listOf("ok", "\uD83D")) }
    }
}
