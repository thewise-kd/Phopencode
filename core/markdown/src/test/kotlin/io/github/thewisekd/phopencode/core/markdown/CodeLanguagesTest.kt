package io.github.thewisekd.phopencode.core.markdown

import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A snippet per language and a keyword in it that the language's grammar colors. */
private val SAMPLES = mapOf(
    SyntaxLanguage.C to ("int main() { return 0; }" to "return"),
    SyntaxLanguage.CPP to ("namespace app {}" to "namespace"),
    SyntaxLanguage.CSHARP to ("public class App {}" to "class"),
    SyntaxLanguage.COFFEESCRIPT to ("unless done" to "unless"),
    SyntaxLanguage.DART to ("final x = 1;" to "final"),
    SyntaxLanguage.GO to ("func main() {}" to "func"),
    SyntaxLanguage.JAVA to ("public class App {}" to "class"),
    SyntaxLanguage.JAVASCRIPT to ("function f() {}" to "function"),
    SyntaxLanguage.KOTLIN to ("fun main() {}" to "fun"),
    SyntaxLanguage.PERL to ("my \$x = 1;" to "my"),
    SyntaxLanguage.PHP to ("function f() {}" to "function"),
    SyntaxLanguage.PYTHON to ("def f(): pass" to "def"),
    SyntaxLanguage.RUBY to ("def f; end" to "def"),
    SyntaxLanguage.RUST to ("fn main() {}" to "fn"),
    SyntaxLanguage.SHELL to ("pushd src" to "pushd"),
    SyntaxLanguage.SWIFT to ("func f() {}" to "func"),
    SyntaxLanguage.TYPESCRIPT to ("interface A {}" to "interface"),
)

/**
 * The declared code languages and their Highlights mapping. Robolectric runs it only so the
 * Pi's `[26]` test filter picks it up; it touches no Android API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class CodeLanguagesTest {
    @Test
    fun `every Highlights language except the generic one is declared`() {
        val declared = CodeLanguages.supported.values.toSet()
        val expected = SyntaxLanguage.entries.toSet() - SyntaxLanguage.DEFAULT

        assertEquals(expected, declared)
    }

    @Test
    fun `every declared language name highlights a keyword of that language`() {
        for ((name, language) in CodeLanguages.supported) {
            val (code, keyword) = checkNotNull(SAMPLES[language]) { "no sample for $language" }
            val start = code.indexOf(keyword)
            val highlights = Highlights.Builder(code = code, language = language, theme = SyntaxThemes.default())
                .build()
                .getHighlights()
                .filterIsInstance<ColorHighlight>()

            assertTrue(
                highlights.any { it.location.start == start && it.location.end == start + keyword.length },
                "`$name` does not highlight `$keyword`",
            )
        }
    }

    @Test
    fun `common aliases map to their language`() {
        val aliases = mapOf(
            "kt" to SyntaxLanguage.KOTLIN,
            "kts" to SyntaxLanguage.KOTLIN,
            "js" to SyntaxLanguage.JAVASCRIPT,
            "ts" to SyntaxLanguage.TYPESCRIPT,
            "py" to SyntaxLanguage.PYTHON,
            "sh" to SyntaxLanguage.SHELL,
            "bash" to SyntaxLanguage.SHELL,
            "c++" to SyntaxLanguage.CPP,
            "cs" to SyntaxLanguage.CSHARP,
            "rs" to SyntaxLanguage.RUST,
            "rb" to SyntaxLanguage.RUBY,
            "golang" to SyntaxLanguage.GO,
        )
        for ((alias, language) in aliases) {
            assertEquals(language, CodeLanguages.find(alias), alias)
        }
    }

    @Test
    fun `the fence info string is read case-insensitively up to the first whitespace`() {
        assertEquals(SyntaxLanguage.KOTLIN, CodeLanguages.find("Kotlin"))
        assertEquals(SyntaxLanguage.PYTHON, CodeLanguages.find("  python title=\"a.py\""))
        assertEquals(SyntaxLanguage.KOTLIN, CodeLanguages.find("kotlin\ttitle=\"a.kt\""))
        assertEquals(SyntaxLanguage.RUST, CodeLanguages.find("rust\u000B\u000Cextra"))
    }

    @Test
    fun `an unknown, empty or missing language has no highlighter`() {
        assertNull(CodeLanguages.find("brainfuck"))
        assertNull(CodeLanguages.find(""))
        assertNull(CodeLanguages.find("   "))
        assertNull(CodeLanguages.find(null))
        assertNull(CodeLanguages.find("default"))
    }
}
