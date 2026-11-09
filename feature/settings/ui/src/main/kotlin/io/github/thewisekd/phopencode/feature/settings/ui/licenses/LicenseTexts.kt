package io.github.thewisekd.phopencode.feature.settings.ui.licenses

// License names, sources and texts are legal text and proper names: they are shown as they are, in
// every language. Only the screen's own words are translated.

/** Where sora-editor's source code is, from its POM. */
internal const val SORA_EDITOR_SOURCE = "https://github.com/Rosemoe/sora-editor"

/** Where this app's complete source code is. */
internal const val APP_SOURCE = "https://github.com/thewise-kd/Phopencode"

/** Where the Eclipse Foundation offers the source of its EPL content, from the artifact's about.html. */
internal const val ECLIPSE_SOURCE = "https://www.eclipse.org"

/** The sora-editor version the app ships, for the LGPL notice. */
internal val SORA_EDITOR_VERSION: String
    get() = LIBRARIES.first { it.coordinate == "io.github.rosemoe:editor" }.version

/**
 * A license the libraries use, with its full text in the app's assets.
 *
 * [title] names the text; [shortName] is what a library's row shows.
 */
internal enum class LicenseText(val title: String, val shortName: String, val asset: String) {
    APACHE_2_0("Apache License 2.0", "Apache-2.0", "licenses/Apache-2.0.txt"),
    LGPL_2_1("GNU Lesser General Public License v2.1", "LGPL-2.1", "licenses/LGPL-2.1.txt"),
    EPL_2_0("Eclipse Public License 2.0", "EPL-2.0", "licenses/EPL-2.0.txt"),
    BSD_3_CLAUSE_PROTOBUF(
        "BSD 3-Clause License (repackaged Protocol Buffers)",
        "BSD-3-Clause",
        "licenses/BSD-3-Clause-protobuf.txt",
    ),
    BOUNCY_CASTLE("Bouncy Castle Licence", "Bouncy Castle Licence (MIT)", "licenses/BouncyCastle.txt"),
    MIT_JCODINGS("MIT License (JCodings)", "MIT", "licenses/MIT-JCodings.txt"),
    MIT_JONI("MIT License (Joni)", "MIT", "licenses/MIT-Joni.txt"),
    MIT_SLF4J("MIT License (SLF4J)", "MIT", "licenses/MIT-SLF4J.txt"),
}

/**
 * A directory of TextMate grammars or themes `core:codeview` ships, with its license files, which
 * are copied unchanged under `licenses/textmate/` in the assets. See `core:codeview`'s NOTICE.md.
 */
internal data class TextMateNotice(
    val name: String,
    val directory: String,
    val source: String,
    val license: String,
    val licenseFiles: List<String>,
) {
    val licenseAssets: List<String> get() = licenseFiles.map { "licenses/textmate/$directory/$it" }
}

private val VSCODE = listOf("LICENSE", "LICENSE.vscode")

/** Every grammar and theme directory of `core:codeview`, as its NOTICE.md lists them. */
internal val TEXTMATE_NOTICES: List<TextMateNotice> = listOf(
    TextMateNotice("C", "grammars/c", "https://github.com/jeff-hykin/better-c-syntax", "MIT (Jeff Hykin)", VSCODE),
    TextMateNotice(
        "CSS",
        "grammars/css",
        "https://github.com/microsoft/vscode-css",
        "MIT (Microsoft), with the TextMate css bundle notice",
        VSCODE,
    ),
    TextMateNotice("Go", "grammars/go", "https://github.com/worlpaker/go-syntax", "MIT (Furkan Ozalp)", VSCODE),
    TextMateNotice(
        "Java",
        "grammars/java",
        "https://github.com/atom/language-java",
        "MIT (GitHub Inc.), with the TextMate java bundle notice",
        VSCODE,
    ),
    TextMateNotice(
        "JavaScript",
        "grammars/javascript",
        "https://github.com/microsoft/TypeScript-TmLanguage",
        "MIT (Microsoft)",
        VSCODE,
    ),
    TextMateNotice(
        "JSON",
        "grammars/json",
        "https://github.com/microsoft/vscode-JSON.tmLanguage",
        "MIT (simonzack, zertosh, benvie)",
        VSCODE,
    ),
    TextMateNotice(
        "Kotlin",
        "grammars/kotlin",
        "https://github.com/fwcd/vscode-kotlin",
        "MIT (George Fraser, fwcd)",
        listOf("LICENSE"),
    ),
    TextMateNotice(
        "Markdown",
        "grammars/markdown",
        "https://github.com/microsoft/vscode-markdown-tm-grammar",
        "MIT (Microsoft)",
        VSCODE,
    ),
    TextMateNotice(
        "Python",
        "grammars/python",
        "https://github.com/MagicStack/MagicPython",
        "MIT (MagicStack Inc.)",
        VSCODE,
    ),
    TextMateNotice(
        "Rust",
        "grammars/rust",
        "https://github.com/dustypomerleau/rust-syntax",
        "MIT (Dustin Pomerleau)",
        VSCODE,
    ),
    TextMateNotice(
        "Shell",
        "grammars/shell",
        "https://github.com/jeff-hykin/better-shell-syntax",
        "MIT (Jeff Hykin)",
        VSCODE,
    ),
    TextMateNotice("SQL", "grammars/sql", "https://github.com/microsoft/vscode-mssql", "MIT (Microsoft)", VSCODE),
    TextMateNotice(
        "TOML",
        "grammars/toml",
        "https://github.com/tamasfe/taplo",
        "MIT (Ferenc Tamás)",
        listOf("LICENSE"),
    ),
    TextMateNotice(
        "TypeScript",
        "grammars/typescript",
        "https://github.com/microsoft/TypeScript-TmLanguage",
        "MIT (Microsoft)",
        VSCODE,
    ),
    TextMateNotice(
        "XML",
        "grammars/xml",
        "https://github.com/atom/language-xml",
        "MIT (GitHub Inc.), with the TextMate xml bundle notice",
        VSCODE,
    ),
    TextMateNotice(
        "YAML",
        "grammars/yaml",
        "https://github.com/RedCMD/YAML-Syntax-Highlighter",
        "MIT (RedCMD)",
        VSCODE,
    ),
    TextMateNotice(
        "Visual Studio Code Dark+ and Light+",
        "themes",
        "https://github.com/microsoft/vscode",
        "MIT (Microsoft)",
        listOf("LICENSE"),
    ),
)
