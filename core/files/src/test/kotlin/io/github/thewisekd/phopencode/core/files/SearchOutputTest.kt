package io.github.thewisekd.phopencode.core.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchOutputTest {
    private val dir = "/r/d"

    private fun record(path: String, line: Int, text: String): ByteArray =
        path.encodeToByteArray() + byteArrayOf(0) + "$line:$text\n".encodeToByteArray()

    private fun parse(vararg records: ByteArray, max: Int = 100, maxChars: Int = 1000) =
        SearchOutput.parse(records.fold(ByteArray(0)) { a, b -> a + b }, dir, max, maxChars)

    @Test
    fun `colons in names and in lines stay where they belong`() {
        val parsed = parse(record("$dir/a:1:b.txt", 7, "x: 2: y"), record("$dir/c", 12, ":"))
        assertEquals(
            listOf(ContentMatch("$dir/a:1:b.txt", 7, "x: 2: y"), ContentMatch("$dir/c", 12, ":")),
            parsed.matches,
        )
        assertFalse(parsed.cut)
    }

    @Test
    fun `newlines, quotes and Turkish in names survive`() {
        val names = listOf("new\nline.txt", "it's \"q\".txt", "çğıöşü İ.txt", "\$(touch x)", "-rf")
        val parsed = parse(*names.map { record("$dir/$it", 1, "hit") }.toTypedArray())
        assertEquals(names.map { "$dir/$it" }, parsed.matches.map { it.path })
    }

    @Test
    fun `a cut last record is dropped and reported`() {
        val whole = record("$dir/a", 1, "one")
        val cut = record("$dir/b", 2, "two").let { it.copyOf(it.size - 2) }
        val parsed = SearchOutput.parse(whole + cut, dir, 100, 1000)
        assertEquals(listOf(ContentMatch("$dir/a", 1, "one")), parsed.matches)
        assertTrue(parsed.cut)
        assertTrue(SearchOutput.parse("$dir/x".encodeToByteArray(), dir, 100, 1000).cut)
    }

    @Test
    fun `results stop at the limit`() {
        val parsed = parse(*(1..5).map { record("$dir/f", it, "x") }.toTypedArray(), max = 3)
        assertEquals(3, parsed.matches.size)
        assertTrue(parsed.cut)
        val exact = parse(*(1..3).map { record("$dir/f", it, "x") }.toTypedArray(), max = 3)
        assertFalse(exact.cut)
    }

    @Test
    fun `paths outside the folder are dropped`() {
        val parsed = parse(record("/r/dx/a", 1, "x"), record("/etc/passwd", 1, "x"), record("$dir/ok", 1, "x"))
        assertEquals(listOf("$dir/ok"), parsed.matches.map { it.path })
        assertEquals(listOf("/a"), SearchOutput.parse(record("/a", 1, "x"), "/", 10, 10).matches.map { it.path })
    }

    @Test
    fun `line numbers up to the largest Int parse, larger ones stop the parse`() {
        val parsed = parse(record("$dir/a", Int.MAX_VALUE, "x"), record("$dir/b", 1_000_000_000, "y"))
        assertEquals(listOf(Int.MAX_VALUE, 1_000_000_000), parsed.matches.map { it.line })
        assertFalse(parsed.cut)

        val tooBig = "$dir/c".encodeToByteArray() + byteArrayOf(0) + "2147483648:z\n".encodeToByteArray()
        val over = parse(record("$dir/a", 1, "x"), tooBig)
        assertEquals(listOf("$dir/a"), over.matches.map { it.path })
        assertTrue(over.cut)
    }

    @Test
    fun `malformed output stops parsing safely`() {
        val parsed = parse(record("$dir/a", 1, "x"), "$dir/b".encodeToByteArray() + byteArrayOf(0) + "zz:x\n".encodeToByteArray())
        assertEquals(listOf("$dir/a"), parsed.matches.map { it.path })
        assertTrue(parsed.cut)
    }

    @Test
    fun `long lines are shortened, carriage returns and bad bytes are safe`() {
        val parsed = parse(record("$dir/a", 1, "x".repeat(50)), record("$dir/b", 2, "crlf\r"), max = 10, maxChars = 10)
        assertEquals("x".repeat(10), parsed.matches[0].text)
        assertEquals("crlf", parsed.matches[1].text)
        val bad = "$dir/c".encodeToByteArray() + byteArrayOf(0) + "3:".encodeToByteArray() + byteArrayOf(-1, 65, 10)
        assertEquals("�A", SearchOutput.parse(bad, dir, 10, 10).matches.single().text)
    }
}
