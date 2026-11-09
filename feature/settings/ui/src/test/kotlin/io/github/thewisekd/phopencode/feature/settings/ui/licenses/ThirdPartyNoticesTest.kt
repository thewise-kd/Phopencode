package io.github.thewisekd.phopencode.feature.settings.ui.licenses

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notices cover what the release app ships, offline and without Gradle:
 * - `release-runtime-classpath.txt`, from `:app`'s release runtime classpath, is the shipped set;
 *   the screen's [LIBRARIES] and THIRD_PARTY_NOTICES.md list exactly that set, each with its license;
 * - every runtime library of the version catalog is in that set, so a new one cannot be added
 *   without its notice;
 * - the LGPL and EPL notices are there, and every license text ships with the app;
 * - every TextMate grammar and theme of `core:codeview` ships with its license files, unchanged.
 */
class ThirdPartyNoticesTest {
    private val repo: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "gradle/libs.versions.toml").isFile && File(it, "settings.gradle.kts").isFile }

    private val module = File(repo, "feature/settings/ui")
    private val assets = File(module, "src/main/assets")
    private val codeviewAssets = File(repo, "core/codeview/src/main/assets/codeview")
    private val notices: String by lazy { File(repo, "THIRD_PARTY_NOTICES.md").readText() }

    private val shipped: List<String> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/licenses/release-runtime-classpath.txt"))
        stream.bufferedReader().use { it.readLines() }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    @Test
    fun `the shipped list is not empty and has no duplicates`() {
        assertTrue(shipped.size > 100)
        assertEquals(shipped.size, shipped.toSet().size)
        shipped.forEach { assertEquals("not group:artifact:version: $it", 3, it.split(":").size) }
    }

    @Test
    fun `the screen lists exactly the shipped artifacts`() {
        val listed = LIBRARIES.map { "${it.coordinate}:${it.version}" }
        assertEquals(listed.size, listed.toSet().size)
        assertEquals(shipped.toSortedSet(), listed.toSortedSet())
    }

    @Test
    fun `the notices file lists every shipped artifact with its version and license`() {
        val missing = LIBRARIES.filterNot { library ->
            notices.lines().any { it.trim() == library.noticesRow() }
        }
        assertTrue(
            "THIRD_PARTY_NOTICES.md has no row for:\n" + missing.joinToString("\n") { it.noticesRow() },
            missing.isEmpty(),
        )
        val rows = notices.lines().count { line -> LIBRARIES.any { line.trim() == it.noticesRow() } }
        assertEquals(LIBRARIES.size, rows)
    }

    @Test
    fun `every runtime library of the version catalog is shipped`() {
        val shippedModules = shipped.map { it.substringBeforeLast(":") }.toSet()
        val missing = catalogModules().filterKeys { it !in NOT_SHIPPED }.filterValues { module ->
            listOf(module, "$module-android", "$module-jvm").none { it in shippedModules }
        }
        assertTrue("catalog libraries missing from the shipped list: $missing", missing.isEmpty())
    }

    @Test
    fun `every license the libraries use ships as text and is named in the notices`() {
        LicenseText.entries.forEach { license ->
            val file = File(assets, license.asset)
            assertTrue("missing ${license.asset}", file.isFile && file.readText().isNotBlank())
            assertTrue("THIRD_PARTY_NOTICES.md does not name ${license.title}", license.title in notices)
        }
        assertEquals(LicenseText.entries.toSet(), LIBRARIES.map { it.license }.toSet())
    }

    @Test
    fun `every MIT, BSD and Bouncy Castle text carries its copyright line`() {
        val notices = LicenseText.entries.filter { license ->
            license.shortName == "MIT" || license.shortName.startsWith("BSD") ||
                license == LicenseText.BOUNCY_CASTLE
        }
        assertEquals(
            setOf(
                LicenseText.MIT_JCODINGS, LicenseText.MIT_JONI, LicenseText.MIT_SLF4J,
                LicenseText.BSD_3_CLAUSE_PROTOBUF, LicenseText.BOUNCY_CASTLE,
            ),
            notices.toSet(),
        )
        val copyright = Regex("""^Copyright (\(c\) )?\d{4}(\s*-\s*\d{4})? \S.*$""", RegexOption.MULTILINE)
        notices.forEach { license ->
            val text = File(assets, license.asset).readText()
            assertTrue("${license.asset} has no copyright line", copyright.containsMatchIn(text))
        }
        val owners = mapOf(
            LicenseText.MIT_JCODINGS to "JRuby Team",
            LicenseText.MIT_JONI to "JRuby Team",
            LicenseText.MIT_SLF4J to "QOS.ch",
            LicenseText.BSD_3_CLAUSE_PROTOBUF to "Google Inc.",
            LicenseText.BOUNCY_CASTLE to "The Legion of the Bouncy Castle Inc.",
        )
        owners.forEach { (license, owner) ->
            val line = copyright.findAll(File(assets, license.asset).readText()).map { it.value }.toList()
            assertTrue("${license.asset} does not name $owner: $line", line.any { owner in it })
        }
    }

    @Test
    fun `the LGPL libraries carry the LGPL notice and the full LGPL text`() {
        val lgpl = LIBRARIES.filter { it.license == LicenseText.LGPL_2_1 }.map { it.coordinate }
        assertEquals(listOf("io.github.rosemoe:editor", "io.github.rosemoe:language-textmate"), lgpl)

        val section = notices.section("## GNU Lesser General Public License notice")
        assertTrue(SORA_EDITOR_SOURCE in section)
        assertTrue(APP_SOURCE in section)
        assertTrue("replace" in section)
        assertTrue("unmodified" in section)

        val text = File(assets, LicenseText.LGPL_2_1.asset).readText()
        assertTrue(text.trimStart().startsWith("GNU LESSER GENERAL PUBLIC LICENSE"))
        assertTrue("Version 2.1, February 1999" in text)
        assertTrue("END OF TERMS AND CONDITIONS" in text)
    }

    @Test
    fun `the EPL library says where its source is`() {
        val epl = LIBRARIES.filter { it.license == LicenseText.EPL_2_0 }.map { it.coordinate }
        assertEquals(listOf("org.eclipse.jdt:org.eclipse.jdt.annotation"), epl)
        assertTrue(ECLIPSE_SOURCE in notices.section("## Eclipse Public License notice"))
        assertTrue("Eclipse Public License - v 2.0" in File(assets, LicenseText.EPL_2_0.asset).readText())
    }

    @Test
    fun `every TextMate grammar and theme license ships unchanged`() {
        val originals = codeviewAssets.walkTopDown().filter { it.isFile && it.name.startsWith("LICENSE") }.toList()
        assertTrue(originals.size > 20)
        originals.forEach { original ->
            val relative = original.relativeTo(codeviewAssets).invariantSeparatorsPath
            val copy = File(assets, "licenses/textmate/$relative")
            assertTrue("missing copy of $relative", copy.isFile)
            assertTrue("$relative differs from core:codeview's", original.readBytes().contentEquals(copy.readBytes()))
        }
        val listed = TEXTMATE_NOTICES.flatMap { notice -> notice.licenseAssets }.toSet()
        val expected = originals.map { "licenses/textmate/" + it.relativeTo(codeviewAssets).invariantSeparatorsPath }
        assertEquals(expected.toSortedSet(), listed.toSortedSet())
    }

    @Test
    fun `every TextMate directory is on the screen and in the notices`() {
        val directories = (File(codeviewAssets, "grammars").listFiles()!!.filter { it.isDirectory }
            .map { "grammars/${it.name}" } + "themes").toSortedSet()
        assertEquals(directories, TEXTMATE_NOTICES.map { it.directory }.toSortedSet())
        val section = notices.section("## TextMate grammars and themes")
        TEXTMATE_NOTICES.forEach { notice ->
            assertTrue("no row for ${notice.directory}", "`${notice.directory}/`" in section)
            assertTrue(notice.source in section)
            assertTrue(notice.license in section)
        }
    }

    /** Library aliases of the catalog, by alias, as `group:artifact`. */
    private fun catalogModules(): Map<String, String> {
        val catalog = File(repo, "gradle/libs.versions.toml").readLines()
        val start = catalog.indexOfFirst { it.trim() == "[libraries]" }
        val entry = Regex("""^([\w-]+)\s*=\s*\{\s*module\s*=\s*"([^"]+)"""")
        return catalog.drop(start + 1)
            .takeWhile { !it.trim().startsWith("[") }
            .mapNotNull { entry.find(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun String.section(heading: String): String {
        val start = indexOf("\n$heading\n")
        assertTrue("THIRD_PARTY_NOTICES.md has no \"$heading\"", start >= 0)
        val end = indexOf("\n## ", start + heading.length + 1).takeIf { it >= 0 } ?: length
        return substring(start, end)
    }

    private companion object {
        /** Catalog libraries that never reach the release app: build plugins, tests, debug tools, platforms. */
        val NOT_SHIPPED = setOf(
            "android-gradle-plugin", "kotlin-gradle-plugin", "kotlin-serialization-gradle-plugin",
            "compose-compiler-gradle-plugin", "kotlinx-coroutines-test", "jsch", "compose-bom",
            "compose-ui-tooling", "compose-ui-test-junit4", "compose-ui-test-manifest", "junit-bom",
            "junit-jupiter", "junit-platform-launcher", "kotlin-test", "turbine", "okhttp-mockwebserver3",
            "okhttp-mockwebserver3-junit5", "mina-sshd-core", "mina-sshd-sftp", "slf4j-nop", "koin-test",
            "koin-test-junit4", "junit4", "robolectric", "androidx-test-core", "androidx-test-runner",
            "androidx-test-ext-junit", "androidx-test-espresso-core",
        )
    }
}

/** The row THIRD_PARTY_NOTICES.md keeps for [this]. */
private fun Library.noticesRow(): String = "| `$coordinate` | $version | ${license.shortName} |"
