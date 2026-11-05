package io.github.thewisekd.phopencode.core.codeview

import java.util.Locale

/**
 * The languages [CodeView] highlights. Pick one with [fromFileName] or [fromExtension];
 * anything not listed is [PlainText], which shows the text without colors.
 *
 * [Html] is highlighted with the XML grammar (tags, attributes, strings, comments; no
 * embedded script or style), and [Cpp] with the C grammar: no permissively licensed
 * HTML or C++ TextMate grammar is bundled. See `assets/codeview/NOTICE.md`.
 */
enum class CodeLanguage(
    /** Lowercase file extensions, without the dot, that select this language. */
    val extensions: Set<String>,
    /** Lowercase whole file names, such as `.bashrc`, that select this language. */
    internal val fileNames: Set<String> = emptySet(),
) {
    Kotlin(setOf("kt", "kts")),
    Java(setOf("java")),
    JavaScript(setOf("js", "jsx", "mjs", "cjs")),
    TypeScript(setOf("ts", "mts", "cts")),
    Tsx(setOf("tsx")),
    Python(setOf("py", "pyi", "pyw")),
    Go(setOf("go")),
    Rust(setOf("rs")),
    C(setOf("c", "h")),
    Cpp(setOf("cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "h++")),
    Shell(
        setOf("sh", "bash", "zsh", "ksh"),
        setOf(".bashrc", ".bash_profile", ".bash_login", ".bash_logout", ".bash_aliases", ".profile", ".zshrc", ".zprofile", ".zshenv", ".zlogin"),
    ),
    Json(setOf("json", "jsonc")),
    Yaml(setOf("yaml", "yml")),
    Toml(setOf("toml")),
    Markdown(setOf("md", "markdown", "mkd")),
    Html(setOf("html", "htm", "xhtml")),
    Css(setOf("css")),
    Xml(setOf("xml", "svg", "xsd", "xsl", "xslt", "plist")),
    Sql(setOf("sql")),
    PlainText(emptySet()),
    ;

    companion object {
        private val byExtension: Map<String, CodeLanguage> =
            entries.flatMap { language -> language.extensions.map { it to language } }.toMap()
        private val byFileName: Map<String, CodeLanguage> =
            entries.flatMap { language -> language.fileNames.map { it to language } }.toMap()

        /**
         * The language of [fileName], which may be a bare name or a path with `/` or `\`
         * separators; only its last segment counts. Whole names such as `.bashrc` are
         * checked first, then the extension after the last dot, ignoring case. A name
         * without an extension, or with one not listed, is [PlainText].
         */
        fun fromFileName(fileName: String): CodeLanguage {
            val name = fileName.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT)
            byFileName[name]?.let { return it }
            val dot = name.lastIndexOf('.')
            if (dot <= 0) return PlainText
            return fromExtension(name.substring(dot + 1))
        }

        /** The language of a file extension such as `kt` or `.kt`, ignoring case; [PlainText] if not listed. */
        fun fromExtension(extension: String): CodeLanguage =
            byExtension[extension.removePrefix(".").lowercase(Locale.ROOT)] ?: PlainText
    }
}
