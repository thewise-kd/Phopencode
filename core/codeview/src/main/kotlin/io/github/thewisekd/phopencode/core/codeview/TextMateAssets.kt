package io.github.thewisekd.phopencode.core.codeview

/** The asset directory of this module; `NOTICE.md` in it lists every source and license. */
internal const val ASSET_ROOT = "codeview"

/** One TextMate grammar file and the scope name it declares. */
internal data class GrammarFile(val file: String, val scopeName: String)

/**
 * The grammar files of a language under `codeview/grammars/<directory>/`. [files] load in
 * order and [main] last, so the grammars it includes by scope name are there first.
 */
internal class TextMateGrammar(val directory: String, val main: GrammarFile, dependencies: List<GrammarFile> = emptyList()) {
    val files: List<GrammarFile> = dependencies + main

    /** VS Code's language configuration: brackets, comments, auto-closing pairs and indentation. */
    val languageConfiguration: String = path("language-configuration.json")

    fun path(file: GrammarFile): String = path(file.file)

    private fun path(name: String) = "$ASSET_ROOT/grammars/$directory/$name"
}

/** Which grammar highlights each [CodeLanguage]. */
internal object TextMateGrammars {
    private val c = TextMateGrammar("c", GrammarFile("c.tmLanguage.json", "source.c"))
    private val typescript = TextMateGrammar("typescript", GrammarFile("TypeScript.tmLanguage.json", "source.ts"))
    private val tsx = TextMateGrammar("typescript", GrammarFile("TypeScriptReact.tmLanguage.json", "source.tsx"))
    private val xml = TextMateGrammar("xml", GrammarFile("xml.tmLanguage.json", "text.xml"))
    private val yaml = TextMateGrammar(
        "yaml",
        GrammarFile("yaml.tmLanguage.json", "source.yaml"),
        listOf(
            GrammarFile("yaml-1.0.tmLanguage.json", "source.yaml.1.0"),
            GrammarFile("yaml-1.1.tmLanguage.json", "source.yaml.1.1"),
            GrammarFile("yaml-1.2.tmLanguage.json", "source.yaml.1.2"),
            GrammarFile("yaml-1.3.tmLanguage.json", "source.yaml.1.3"),
            GrammarFile("yaml-embedded.tmLanguage.json", "source.yaml.embedded"),
        ),
    )

    private fun single(directory: String, file: String, scopeName: String) =
        TextMateGrammar(directory, GrammarFile(file, scopeName))

    private val byLanguage: Map<CodeLanguage, TextMateGrammar> = mapOf(
        CodeLanguage.Kotlin to single("kotlin", "kotlin.tmLanguage.json", "source.kotlin"),
        CodeLanguage.Java to single("java", "java.tmLanguage.json", "source.java"),
        CodeLanguage.JavaScript to single("javascript", "JavaScript.tmLanguage.json", "source.js"),
        CodeLanguage.TypeScript to typescript,
        CodeLanguage.Tsx to tsx,
        CodeLanguage.Python to single("python", "MagicPython.tmLanguage.json", "source.python"),
        CodeLanguage.Go to single("go", "go.tmLanguage.json", "source.go"),
        CodeLanguage.Rust to single("rust", "rust.tmLanguage.json", "source.rust"),
        CodeLanguage.C to c,
        CodeLanguage.Cpp to c,
        CodeLanguage.Shell to single("shell", "shell-unix-bash.tmLanguage.json", "source.shell"),
        CodeLanguage.Json to single("json", "JSONC.tmLanguage.json", "source.json.comments"),
        CodeLanguage.Yaml to yaml,
        CodeLanguage.Toml to single("toml", "toml.tmLanguage.json", "source.toml"),
        CodeLanguage.Markdown to single("markdown", "markdown.tmLanguage.json", "text.html.markdown"),
        CodeLanguage.Html to xml,
        CodeLanguage.Css to single("css", "css.tmLanguage.json", "source.css"),
        CodeLanguage.Xml to xml,
        CodeLanguage.Sql to single("sql", "sql.tmLanguage.json", "source.sql"),
    )

    /** The grammar of [language], or null for [CodeLanguage.PlainText]. */
    fun of(language: CodeLanguage): TextMateGrammar? = byLanguage[language]
}

/** The dark and light editor themes, merged from VS Code's Dark+ and Light+. */
internal object TextMateThemes {
    fun path(dark: Boolean): String = "$ASSET_ROOT/themes/${if (dark) "dark" else "light"}.json"

    fun name(dark: Boolean): String = if (dark) "phopencode Dark+" else "phopencode Light+"
}
