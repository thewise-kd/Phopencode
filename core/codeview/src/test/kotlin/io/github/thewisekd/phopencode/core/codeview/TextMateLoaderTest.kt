package io.github.thewisekd.phopencode.core.codeview

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import org.eclipse.tm4e.core.grammar.IStateStack
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A sample per language whose tokens must carry scopes beyond the grammar's root scope. */
internal val SAMPLES = mapOf(
    CodeLanguage.Kotlin to "fun main() { val x = \"hi\" }",
    CodeLanguage.Java to "public class A { int x = 1; }",
    CodeLanguage.JavaScript to "const x = () => 'hi';",
    CodeLanguage.TypeScript to "let x: number = 1;",
    CodeLanguage.Tsx to "const a = <div className=\"x\" />;",
    CodeLanguage.Python to "def main(): return 'hi'",
    CodeLanguage.Go to "func main() { return }",
    CodeLanguage.Rust to "fn main() { let x = 1; }",
    CodeLanguage.C to "int main(void) { return 0; }",
    CodeLanguage.Cpp to "int main() { return 0; }",
    CodeLanguage.Shell to "if [ -n \"\$HOME\" ]; then echo hi; fi",
    CodeLanguage.Json to "{\"key\": [1, true, null]}",
    CodeLanguage.Yaml to "key: value # comment",
    CodeLanguage.Toml to "[package]\nname = \"x\"",
    CodeLanguage.Markdown to "# Title with `code`",
    CodeLanguage.Html to "<div class=\"x\">text</div>",
    CodeLanguage.Css to "body { color: red; }",
    CodeLanguage.Xml to "<root attr=\"x\"><!-- c --></root>",
    CodeLanguage.Sql to "SELECT id FROM users WHERE id = 1;",
)

/** How long a held grammar load waits before the test's watchdog lets it go. */
private const val WATCHDOG_S = 5L

/** Main-thread work that takes longer than this has waited for the held load. */
private const val BLOCK_LIMIT_MS = 2_000L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class TextMateLoaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Opens packaged assets and records each path. */
    private inner class CountingAssets : (String) -> InputStream {
        val opened = mutableListOf<String>()

        override fun invoke(path: String): InputStream {
            opened += path
            return context.assets.open(path)
        }
    }

    @Test
    fun `the samples cover every highlighted language`() {
        assertEquals(CodeLanguage.entries.toSet() - CodeLanguage.PlainText, SAMPLES.keys)
    }

    @Test
    fun `every grammar loads from the packaged assets and highlights its sample`() {
        val loader = TextMateLoader(CountingAssets())
        val failures = SAMPLES.mapNotNull { (language, sample) ->
            runCatching {
                assertTrue(loader.loadGrammar(language), "$language did not load")
                val scope = TextMateGrammars.of(language)!!.main.scopeName
                val grammar = assertNotNull(loader.grammarRegistry(language)?.findGrammar(scope), "$language")
                var stack: IStateStack? = null
                val scopes = sample.lines().flatMap { line ->
                    val result = grammar.tokenizeLine(line, stack, null)
                    stack = result.ruleStack
                    result.tokens.flatMap { it.scopes }
                }
                assertTrue(scopes.any { it != scope }, "$language: no token scopes in $scopes")
                assertNotNull(loader.grammarRegistry(language)?.findLanguageConfiguration(scope), "$language: no language configuration")
            }.exceptionOrNull()?.let { "$language: $it" }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `the rules patched in the assets compile`() {
        val loader = TextMateLoader(CountingAssets())
        mapOf(
            CodeLanguage.Xml to listOf("<%-- server comment --%>"),
            CodeLanguage.Yaml to listOf("%YAML 1.2", "%TAG !e! tag:example.com,2000:app/", "---", "a: !e!foo b"),
        ).forEach { (language, lines) ->
            assertTrue(loader.loadGrammar(language))
            val grammar = loader.grammarRegistry(language)!!.findGrammar(TextMateGrammars.of(language)!!.main.scopeName)!!
            var stack: IStateStack? = null
            lines.forEach { line ->
                val result = grammar.tokenizeLine(line, stack, null)
                stack = result.ruleStack
            }
        }
    }

    @Test
    fun `every capture in every grammar is a rule object`() {
        val problems = mutableListOf<String>()
        fun walk(node: Any?, path: String) {
            when (node) {
                is JSONObject -> node.keys().forEach { key ->
                    val value = node.get(key)
                    if (key.endsWith("aptures") && value is JSONObject) {
                        value.keys().forEach { capture ->
                            if (value.get(capture) !is JSONObject) problems += "$path/$key/$capture"
                        }
                    }
                    walk(value, "$path/$key")
                }
                is JSONArray -> (0 until node.length()).forEach { walk(node.get(it), "$path[$it]") }
            }
        }
        CodeLanguage.entries.mapNotNull { TextMateGrammars.of(it) }.distinct().forEach { grammar ->
            grammar.files.forEach { file ->
                val path = grammar.path(file)
                walk(JSONObject(context.assets.open(path).bufferedReader().use { it.readText() }), path)
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `the main thread never waits for a grammar that is loading`() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val assets = CountingAssets()
        val loader = TextMateLoader { path ->
            if ("/grammars/rust/" in path) {
                reading.countDown()
                release.await(WATCHDOG_S, TimeUnit.SECONDS)
            }
            assets(path)
        }
        // Kotlin is loaded and highlighted before the slow load starts.
        assertTrue(loader.loadTheme(dark = false))
        loader.applyTheme(dark = false)
        assertTrue(loader.loadGrammar(CodeLanguage.Kotlin))

        val background = thread { loader.loadGrammar(CodeLanguage.Rust) }
        try {
            assertTrue(reading.await(WATCHDOG_S, TimeUnit.SECONDS), "the Rust load did not start")
            val started = System.nanoTime()
            // What an editor does on the main thread when it opens or the theme switches.
            assertTrue(loader.loadTheme(dark = true))
            loader.applyTheme(dark = true)
            val scheme = loader.colorScheme()
            val kotlin = loader.createLanguage(CodeLanguage.Kotlin)
            assertTrue(kotlin is TextMateLanguage)
            assertNull(loader.createLanguage(CodeLanguage.Rust), "a grammar still loading is not ready")
            loader.applyTheme(dark = false)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(release.count == 1L, "the main thread waited until the watchdog released the load")
            assertTrue(elapsedMs < BLOCK_LIMIT_MS, "the main thread took $elapsedMs ms")
            kotlin.destroy()
            assertTrue(scheme is TextMateColorScheme)
        } finally {
            release.countDown()
            background.join(WATCHDOG_S * 1_000)
        }
        assertTrue(loader.loadGrammar(CodeLanguage.Rust))
        assertTrue(loader.createLanguage(CodeLanguage.Rust) is TextMateLanguage)
    }

    @Test
    fun `a grammar that finishes loading takes the theme applied meanwhile`() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val assets = CountingAssets()
        val loader = TextMateLoader { path ->
            if ("/grammars/go/" in path) {
                reading.countDown()
                release.await(WATCHDOG_S, TimeUnit.SECONDS)
            }
            assets(path)
        }
        loader.loadTheme(dark = false)
        loader.loadTheme(dark = true)
        loader.applyTheme(dark = false)
        val background = thread { loader.loadGrammar(CodeLanguage.Go) }
        assertTrue(reading.await(WATCHDOG_S, TimeUnit.SECONDS))
        loader.applyTheme(dark = true)
        release.countDown()
        background.join(WATCHDOG_S * 1_000)

        assertEquals(TextMateThemes.name(dark = true), loader.grammarThemeName(CodeLanguage.Go))
        loader.applyTheme(dark = false)
        assertEquals(TextMateThemes.name(dark = false), loader.grammarThemeName(CodeLanguage.Go))
    }

    @Test
    fun `plain text has no grammar to load`() {
        val assets = CountingAssets()
        val loader = TextMateLoader(assets)
        assertFalse(loader.loadGrammar(CodeLanguage.PlainText))
        assertEquals(emptyList(), assets.opened)
    }

    @Test
    fun `a grammar loads lazily and only once`() {
        val assets = CountingAssets()
        val loader = TextMateLoader(assets)
        assertEquals(emptyList(), assets.opened)
        assertTrue(loader.loadGrammar(CodeLanguage.Go))
        val first = loader.grammarRegistry(CodeLanguage.Go)!!.findGrammar("source.go")
        val opened = assets.opened.toList()
        assertTrue(loader.loadGrammar(CodeLanguage.Go))
        assertSame(first, loader.grammarRegistry(CodeLanguage.Go)!!.findGrammar("source.go"))
        assertEquals(opened, assets.opened, "loading again opened more files")
        assertTrue(opened.all { "/grammars/go/" in it }, "opened more than Go: $opened")
        assertNull(loader.grammarRegistry(CodeLanguage.Rust))
    }

    @Test
    fun `C and C++ share one grammar that loads once`() {
        val assets = CountingAssets()
        val loader = TextMateLoader(assets)
        assertTrue(loader.loadGrammar(CodeLanguage.C))
        assertTrue(loader.loadGrammar(CodeLanguage.Cpp))
        assertEquals(1, assets.opened.count { it.endsWith("c.tmLanguage.json") }, "${assets.opened}")
    }

    @Test
    fun `a grammar that cannot be read falls back to plain text`() {
        var attempts = 0
        val loader = TextMateLoader { path ->
            attempts++
            throw FileNotFoundException(path)
        }
        assertFalse(loader.loadGrammar(CodeLanguage.Sql))
        val afterFirst = attempts
        assertFalse(loader.loadGrammar(CodeLanguage.Sql))
        assertEquals(afterFirst, attempts, "a failed grammar is retried")
        assertNull(loader.createLanguage(CodeLanguage.Sql))
    }

    @Test
    fun `both themes load with their type and can be switched`() {
        val loader = TextMateLoader(CountingAssets())
        listOf(true, false, true).forEach { dark ->
            assertTrue(loader.loadTheme(dark))
            val model = assertNotNull(loader.themes.findThemeByThemeName(TextMateThemes.name(dark)))
            assertEquals(dark, model.isDark)
            loader.applyTheme(dark)
            assertEquals(TextMateThemes.name(dark), loader.themes.currentThemeModel.name)
        }
    }

    @Test
    fun `a theme loads only once`() {
        val assets = CountingAssets()
        val loader = TextMateLoader(assets)
        assertTrue(loader.loadTheme(dark = true))
        assertTrue(loader.loadTheme(dark = true))
        assertEquals(listOf(TextMateThemes.path(dark = true)), assets.opened)
    }

    @Test
    fun `the color scheme follows the applied theme`() {
        val loader = TextMateLoader(CountingAssets())
        loader.loadTheme(dark = true)
        loader.loadTheme(dark = false)
        loader.applyTheme(dark = true)
        val scheme = loader.colorScheme() as TextMateColorScheme
        assertTrue(scheme.isDark)
        loader.applyTheme(dark = false)
        assertFalse(scheme.isDark)
    }

    @Test
    fun `a language is created for a loaded grammar and none for plain text`() {
        val loader = TextMateLoader(CountingAssets())
        loader.loadTheme(dark = false)
        loader.applyTheme(dark = false)
        assertTrue(loader.loadGrammar(CodeLanguage.Kotlin))
        val language = loader.createLanguage(CodeLanguage.Kotlin)
        assertTrue(language is TextMateLanguage)
        language.destroy()
        assertNull(loader.createLanguage(CodeLanguage.PlainText))
        assertNull(loader.createLanguage(CodeLanguage.Rust), "a language whose grammar was never loaded")
    }
}
