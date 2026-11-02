package io.github.thewisekd.phopencode.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The catalog against "Technology choices" in docs/PHASES.md. */
class CatalogTest {
    private val catalog = Repository.file("gradle/libs.versions.toml").readText()
    private val plan = Repository.file("docs/PHASES.md").readText()

    private val versions: Map<String, String> = VERSION.findAll(section("versions"))
        .associate { it.groupValues[1] to it.groupValues[2] }

    private val libraries: Map<String, String> = LIBRARY.findAll(section("libraries"))
        .associate { it.groupValues[1] to it.groupValues[2] }

    private fun section(name: String): String =
        catalog.substringAfter("[$name]\n").substringBefore("\n[")

    @Test
    fun `every version in the technology table is in the catalog`() {
        val table = plan.substringAfter("## Technology choices").substringBefore("Reason for choosing sshj")
        val rows = table.lines().filter { it.startsWith("| ") && !it.startsWith("| CI actions") }
        val planned = rows.drop(1).flatMap { row ->
            PLANNED_VERSION.findAll(row.split("|")[3]).map { it.value }.toList()
        }
        assertTrue(planned.size >= 18, "the table parser found only $planned")
        val missing = planned.filterNot { it in versions.values }
        assertEquals(emptyList(), missing, "versions in docs/PHASES.md that are not in the catalog")
    }

    @Test
    fun `the JSch fallback version is in the catalog`() {
        val fallback = assertNotNull(Regex("""JSch fork \((\d[\d.]*\d)\)""").find(plan)).groupValues[1]
        assertEquals(fallback, versions["jsch"])
    }

    @ParameterizedTest
    @CsvSource(
        "android-gradle-plugin, com.android.tools.build:gradle",
        "kotlin-gradle-plugin, org.jetbrains.kotlin:kotlin-gradle-plugin",
        "kotlin-serialization-gradle-plugin, org.jetbrains.kotlin:kotlin-serialization",
        "compose-compiler-gradle-plugin, org.jetbrains.kotlin:compose-compiler-gradle-plugin",
        "compose-bom, androidx.compose:compose-bom",
        "compose-material3, androidx.compose.material3:material3",
        "compose-ui, androidx.compose.ui:ui",
        "compose-ui-test-junit4, androidx.compose.ui:ui-test-junit4",
        "androidx-navigation-compose, androidx.navigation:navigation-compose",
        "androidx-activity-compose, androidx.activity:activity-compose",
        "koin-core, io.insert-koin:koin-core",
        "koin-android, io.insert-koin:koin-android",
        "koin-androidx-compose, io.insert-koin:koin-androidx-compose",
        "koin-test, io.insert-koin:koin-test",
        "sshj, com.hierynomus:sshj",
        "jsch, com.github.mwiede:jsch",
        "mina-sshd-core, org.apache.sshd:sshd-core",
        "mina-sshd-sftp, org.apache.sshd:sshd-sftp",
        "okhttp, com.squareup.okhttp3:okhttp",
        "okhttp-sse, com.squareup.okhttp3:okhttp-sse",
        "okhttp-mockwebserver3, com.squareup.okhttp3:mockwebserver3",
        "kotlinx-serialization-json, org.jetbrains.kotlinx:kotlinx-serialization-json",
        "kotlinx-coroutines-core, org.jetbrains.kotlinx:kotlinx-coroutines-core",
        "kotlinx-coroutines-test, org.jetbrains.kotlinx:kotlinx-coroutines-test",
        "turbine, app.cash.turbine:turbine",
        "tink-android, com.google.crypto.tink:tink-android",
        "androidx-datastore, androidx.datastore:datastore",
        "androidx-datastore-preferences, androidx.datastore:datastore-preferences",
        "markdown-renderer-m3, com.mikepenz:multiplatform-markdown-renderer-m3",
        "markdown-renderer-code, com.mikepenz:multiplatform-markdown-renderer-code",
        "highlights, dev.snipme:highlights",
        "sora-editor, io.github.rosemoe:editor",
        "sora-editor-language-textmate, io.github.rosemoe:language-textmate",
        "coil-compose, io.coil-kt.coil3:coil-compose",
        "robolectric, org.robolectric:robolectric",
    )
    fun `a planned library has its catalog entry`(alias: String, module: String) {
        assertEquals(module, libraries[alias])
    }

    @Test
    fun `the build plugins are in the catalog`() {
        val plugins = section("plugins")
        listOf(
            "com.android.application",
            "com.android.library",
            "org.jetbrains.kotlin.jvm",
            "org.jetbrains.kotlin.plugin.serialization",
            "org.jetbrains.kotlin.plugin.compose",
        ).forEach { assertTrue("id = \"$it\"" in plugins, "$it is not in [plugins]") }
    }

    @Test
    fun `no version is dynamic or a pre-release`() {
        val unstable = versions.filterValues { UNSTABLE.containsMatchIn(it) }
        assertEquals(emptyMap(), unstable)
    }

    @Test
    fun `the wrapper runs the Gradle version in the catalog`() {
        val wrapper = Repository.file("gradle/wrapper/gradle-wrapper.properties").readText()
        assertTrue("gradle-${versions["gradle"]}-bin.zip" in wrapper, wrapper)
    }

    @Test
    fun `minSdk is 26`() {
        assertEquals("26", versions["android-minSdk"])
    }

    private companion object {
        val VERSION = Regex("""(?m)^([\w-]+) = "([^"]+)"""")
        val LIBRARY = Regex("""(?m)^([\w-]+) = \{ module = "([^"]+)"""")
        val PLANNED_VERSION = Regex("""\d+(\.\d+)+""")
        val UNSTABLE = Regex("""\+|alpha|beta|-rc|-M\d|SNAPSHOT|-dev""", RegexOption.IGNORE_CASE)
    }
}
