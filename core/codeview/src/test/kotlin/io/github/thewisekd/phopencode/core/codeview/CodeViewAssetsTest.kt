package io.github.thewisekd.phopencode.core.codeview

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Checks the files under `src/main/assets` against the grammar table, on the plain JVM.
 * Gradle runs unit tests in the module directory.
 */
class CodeViewAssetsTest {
    private val assets = File("src/main/assets")
    private val root = File(assets, ASSET_ROOT)
    private val notice = File(root, "NOTICE.md")

    private fun asset(path: String) = File(assets, path)

    private val permissive = listOf(
        "Permission is hereby granted, free of charge", // MIT
        "Apache License", // Apache-2.0
        "Redistribution and use in source and binary forms", // BSD
    )
    private val copyleft = listOf("GNU GENERAL PUBLIC", "GNU LESSER", "Eclipse Public License", "Mozilla Public License")

    private fun assertPermissive(license: File) {
        assertTrue(license.isFile, "missing ${license.path}")
        val text = license.readText()
        assertTrue(permissive.any { it in text }, "${license.path} is not MIT, Apache-2.0 or BSD")
        copyleft.forEach { assertTrue(it !in text, "${license.path} mentions $it") }
    }

    @Test
    fun `every highlighted language has its grammar files and language configuration`() {
        CodeLanguage.entries.filter { it != CodeLanguage.PlainText }.forEach { language ->
            val grammar = TextMateGrammars.of(language) ?: fail("$language has no grammar")
            grammar.files.forEach { file ->
                val path = grammar.path(file)
                assertTrue(asset(path).isFile, "$language: missing $path")
                assertEquals(
                    file.scopeName,
                    SCOPE_NAME.find(asset(path).readText())?.groupValues?.get(1),
                    "$language: scope name of $path",
                )
            }
            assertTrue(asset(grammar.languageConfiguration).isFile, "$language: missing ${grammar.languageConfiguration}")
            assertEquals(grammar.files.last(), grammar.main, "$language: the main grammar loads last")
        }
        assertEquals(null, TextMateGrammars.of(CodeLanguage.PlainText))
    }

    @Test
    fun `every grammar and theme directory carries a permissive license`() {
        val directories = File(root, "grammars").listFiles { file -> file.isDirectory }.orEmpty().toList() +
            File(root, "themes")
        assertTrue(directories.size > 1)
        directories.forEach { directory ->
            assertPermissive(File(directory, "LICENSE"))
            File(directory, "LICENSE.vscode").takeIf { it.exists() }?.let(::assertPermissive)
        }
    }

    @Test
    fun `every asset file sits next to a license or is the notice`() {
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            if (file == notice) return@forEach
            assertTrue(File(file.parentFile, "LICENSE").isFile, "${file.path} has no LICENSE next to it")
        }
    }

    @Test
    fun `no grammar directory is left unused`() {
        val used = CodeLanguage.entries.mapNotNull { TextMateGrammars.of(it)?.directory }.toSet()
        val present = File(root, "grammars").listFiles { file -> file.isDirectory }.orEmpty().map { it.name }.toSet()
        assertEquals(used, present)
    }

    @Test
    fun `every grammar file in the assets is used`() {
        val used = CodeLanguage.entries.mapNotNull { TextMateGrammars.of(it) }
            .flatMap { grammar -> grammar.files.map(grammar::path) }
            .toSet()
        val present = File(root, "grammars").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".tmLanguage.json") }
            .map { it.relativeTo(assets).invariantSeparatorsPath }
            .toSet()
        assertEquals(used, present)
    }

    @Test
    fun `both themes exist and declare their type`() {
        mapOf(true to "dark", false to "light").forEach { (dark, type) ->
            val theme = asset(TextMateThemes.path(dark))
            assertTrue(theme.isFile, "missing ${theme.path}")
            val text = theme.readText()
            assertTrue(""""type": "$type"""" in text, "${theme.path} is not $type")
            assertTrue(""""name": "${TextMateThemes.name(dark)}"""" in text, "${theme.path} has another name")
            assertTrue(""""editor.background"""" in text && """"settings"""" in text, "${theme.path} has no colors")
        }
    }

    @Test
    fun `the notice records each source with its version and license`() {
        assertTrue(notice.isFile, "missing ${notice.path}")
        val text = notice.readText()
        val directories = File(root, "grammars").listFiles { file -> file.isDirectory }.orEmpty().map { "grammars/${it.name}/" } +
            "themes/"
        directories.forEach { directory -> assertTrue("`$directory`" in text, "NOTICE.md does not list $directory") }
        assertTrue("https://github.com/" in text)
        assertTrue(Regex("[0-9a-f]{40}").containsMatchIn(text), "NOTICE.md records no commit")
        assertTrue("MIT" in text)
    }

    private companion object {
        val SCOPE_NAME = Regex(""""scopeName"\s*:\s*"([^"]+)"""")
    }
}
