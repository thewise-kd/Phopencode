package io.github.thewisekd.phopencode.core.markdown

import dev.snipme.highlights.model.SyntaxLanguage
import java.util.Locale

/**
 * The code languages a fenced block is highlighted in, by the names a fence may use. Each
 * maps to a Highlights grammar; any other name, or none, renders as plain code. Highlights'
 * generic `DEFAULT` grammar is left out on purpose: it would color an unknown language with
 * a guess.
 */
internal object CodeLanguages {
    val supported: Map<String, SyntaxLanguage> = mapOf(
        "c" to SyntaxLanguage.C,
        "h" to SyntaxLanguage.C,
        "cpp" to SyntaxLanguage.CPP,
        "c++" to SyntaxLanguage.CPP,
        "cc" to SyntaxLanguage.CPP,
        "cxx" to SyntaxLanguage.CPP,
        "hpp" to SyntaxLanguage.CPP,
        "csharp" to SyntaxLanguage.CSHARP,
        "cs" to SyntaxLanguage.CSHARP,
        "c#" to SyntaxLanguage.CSHARP,
        "coffeescript" to SyntaxLanguage.COFFEESCRIPT,
        "coffee" to SyntaxLanguage.COFFEESCRIPT,
        "dart" to SyntaxLanguage.DART,
        "go" to SyntaxLanguage.GO,
        "golang" to SyntaxLanguage.GO,
        "java" to SyntaxLanguage.JAVA,
        "javascript" to SyntaxLanguage.JAVASCRIPT,
        "js" to SyntaxLanguage.JAVASCRIPT,
        "jsx" to SyntaxLanguage.JAVASCRIPT,
        "mjs" to SyntaxLanguage.JAVASCRIPT,
        "cjs" to SyntaxLanguage.JAVASCRIPT,
        "kotlin" to SyntaxLanguage.KOTLIN,
        "kt" to SyntaxLanguage.KOTLIN,
        "kts" to SyntaxLanguage.KOTLIN,
        "perl" to SyntaxLanguage.PERL,
        "pl" to SyntaxLanguage.PERL,
        "php" to SyntaxLanguage.PHP,
        "python" to SyntaxLanguage.PYTHON,
        "py" to SyntaxLanguage.PYTHON,
        "python3" to SyntaxLanguage.PYTHON,
        "ruby" to SyntaxLanguage.RUBY,
        "rb" to SyntaxLanguage.RUBY,
        "rust" to SyntaxLanguage.RUST,
        "rs" to SyntaxLanguage.RUST,
        "shell" to SyntaxLanguage.SHELL,
        "sh" to SyntaxLanguage.SHELL,
        "bash" to SyntaxLanguage.SHELL,
        "zsh" to SyntaxLanguage.SHELL,
        "swift" to SyntaxLanguage.SWIFT,
        "typescript" to SyntaxLanguage.TYPESCRIPT,
        "ts" to SyntaxLanguage.TYPESCRIPT,
        "tsx" to SyntaxLanguage.TYPESCRIPT,
    )

    /** The grammar for a fence's info string, read up to its first whitespace, or null for plain code. */
    fun find(info: String?): SyntaxLanguage? {
        val name = info?.trim()?.takeWhile { !it.isWhitespace() }?.lowercase(Locale.ROOT)
        return if (name.isNullOrEmpty()) null else supported[name]
    }
}
