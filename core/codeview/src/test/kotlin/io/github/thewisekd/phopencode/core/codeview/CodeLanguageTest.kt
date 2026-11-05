package io.github.thewisekd.phopencode.core.codeview

import org.junit.Test
import kotlin.test.assertEquals

class CodeLanguageTest {
    private val byFileName = mapOf(
        "Main.kt" to CodeLanguage.Kotlin,
        "build.gradle.kts" to CodeLanguage.Kotlin,
        "App.java" to CodeLanguage.Java,
        "index.js" to CodeLanguage.JavaScript,
        "view.jsx" to CodeLanguage.JavaScript,
        "module.mjs" to CodeLanguage.JavaScript,
        "config.cjs" to CodeLanguage.JavaScript,
        "main.ts" to CodeLanguage.TypeScript,
        "types.d.ts" to CodeLanguage.TypeScript,
        "module.mts" to CodeLanguage.TypeScript,
        "module.cts" to CodeLanguage.TypeScript,
        "Page.tsx" to CodeLanguage.Tsx,
        "script.py" to CodeLanguage.Python,
        "stubs.pyi" to CodeLanguage.Python,
        "server.go" to CodeLanguage.Go,
        "lib.rs" to CodeLanguage.Rust,
        "main.c" to CodeLanguage.C,
        "header.h" to CodeLanguage.C,
        "main.cpp" to CodeLanguage.Cpp,
        "main.cc" to CodeLanguage.Cpp,
        "main.cxx" to CodeLanguage.Cpp,
        "header.hpp" to CodeLanguage.Cpp,
        "header.hh" to CodeLanguage.Cpp,
        "run.sh" to CodeLanguage.Shell,
        "run.bash" to CodeLanguage.Shell,
        "run.zsh" to CodeLanguage.Shell,
        ".bashrc" to CodeLanguage.Shell,
        ".bash_profile" to CodeLanguage.Shell,
        ".zshrc" to CodeLanguage.Shell,
        ".profile" to CodeLanguage.Shell,
        "package.json" to CodeLanguage.Json,
        "tsconfig.jsonc" to CodeLanguage.Json,
        "config.yaml" to CodeLanguage.Yaml,
        "ci.yml" to CodeLanguage.Yaml,
        "Cargo.toml" to CodeLanguage.Toml,
        "README.md" to CodeLanguage.Markdown,
        "notes.markdown" to CodeLanguage.Markdown,
        "index.html" to CodeLanguage.Html,
        "page.htm" to CodeLanguage.Html,
        "style.css" to CodeLanguage.Css,
        "AndroidManifest.xml" to CodeLanguage.Xml,
        "icon.svg" to CodeLanguage.Xml,
        "schema.sql" to CodeLanguage.Sql,
        "notes.txt" to CodeLanguage.PlainText,
    )

    @Test
    fun `maps file names to their language`() {
        byFileName.forEach { (name, language) ->
            assertEquals(language, CodeLanguage.fromFileName(name), name)
        }
    }

    @Test
    fun `ignores the case of the extension`() {
        assertEquals(CodeLanguage.Kotlin, CodeLanguage.fromFileName("MAIN.KT"))
        assertEquals(CodeLanguage.Markdown, CodeLanguage.fromFileName("README.Md"))
        assertEquals(CodeLanguage.Shell, CodeLanguage.fromFileName(".BASHRC"))
    }

    @Test
    fun `reads only the last segment of a path`() {
        assertEquals(CodeLanguage.Python, CodeLanguage.fromFileName("/srv/app.kt/tools/run.py"))
        assertEquals(CodeLanguage.Go, CodeLanguage.fromFileName("""C:\src\main.go"""))
        assertEquals(CodeLanguage.PlainText, CodeLanguage.fromFileName("/srv/src.kt/Makefile"))
        assertEquals(CodeLanguage.Shell, CodeLanguage.fromFileName("/home/user/.zshrc"))
    }

    @Test
    fun `falls back to plain text`() {
        listOf(
            "",
            "Makefile",
            "Dockerfile",
            "archive.tar.gz",
            "photo.png",
            "trailing.",
            ".hidden",
            "kt",
            "/srv/project/",
            "file.unknownext",
        ).forEach { name -> assertEquals(CodeLanguage.PlainText, CodeLanguage.fromFileName(name), name) }
    }

    @Test
    fun `maps a bare extension with or without its dot`() {
        assertEquals(CodeLanguage.Kotlin, CodeLanguage.fromExtension("kt"))
        assertEquals(CodeLanguage.Kotlin, CodeLanguage.fromExtension(".KT"))
        assertEquals(CodeLanguage.Rust, CodeLanguage.fromExtension("rs"))
        assertEquals(CodeLanguage.PlainText, CodeLanguage.fromExtension(""))
        assertEquals(CodeLanguage.PlainText, CodeLanguage.fromExtension("exe"))
    }

    @Test
    fun `every extension maps back to the language that declares it`() {
        CodeLanguage.entries.forEach { language ->
            language.extensions.forEach { extension ->
                assertEquals(language, CodeLanguage.fromExtension(extension), extension)
                assertEquals(language, CodeLanguage.fromFileName("file.$extension"), extension)
            }
        }
    }

    @Test
    fun `plain text declares no extension and every other language at least one`() {
        assertEquals(emptySet(), CodeLanguage.PlainText.extensions)
        CodeLanguage.entries.filter { it != CodeLanguage.PlainText }.forEach { language ->
            assert(language.extensions.isNotEmpty()) { "$language has no extension" }
        }
    }
}
