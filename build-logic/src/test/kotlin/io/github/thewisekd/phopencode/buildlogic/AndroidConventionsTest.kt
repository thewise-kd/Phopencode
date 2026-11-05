package io.github.thewisekd.phopencode.buildlogic

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The convention plugins P0.5 applies, used the way P0.5 will use them. There is no
 * Android SDK here, so these stop short of anything that needs aapt2 or android.jar:
 * they prove configuration and the resolved Android dependency graph.
 */
@Tag("functional")
class AndroidConventionsTest {
    private val build = BuildCopy

    @BeforeEach
    fun addAndroidModules() {
        val plugins = mapOf(
            ":core:storage" to listOf("phopencode.android.library"),
            ":core:ui" to listOf("phopencode.android.library", "phopencode.android.compose"),
            ":core:markdown" to listOf("phopencode.android.library", "phopencode.android.compose"),
            ":core:codeview" to listOf("phopencode.android.library", "phopencode.android.compose"),
            ":app" to listOf("phopencode.android.application"),
        ) + ModuleMap.featureUi.associateWith { listOf("phopencode.feature.ui") }
        plugins.forEach { (module, ids) ->
            build.add(
                module.removePrefix(":").replace(':', '/') + "/build.gradle.kts",
                "plugins {\n" + ids.joinToString("") { "    id(\"$it\")\n" } + "}\n",
            )
        }
    }

    @AfterEach
    fun restore() = build.restore()

    private fun printAndroidSettings(module: String) {
        build.edit("$module/build.gradle.kts") {
            it + """

                val androidSettings = extensions.getByType<com.android.build.api.dsl.CommonExtension>().run {
                    "namespace=" + namespace + " compileSdk=" + compileSdk + " minSdk=" + defaultConfig.minSdk
                }
                tasks.register("printAndroidSettings") {
                    doLast { println(androidSettings) }
                }
            """.trimIndent() + "\n"
        }
    }

    @Test
    fun `the library convention sets the namespace and SDK levels`() {
        printAndroidSettings("core/ui")
        val output = build.succeeds(":core:ui:printAndroidSettings", NO_CACHE, sdk = build.emptySdk()).output
        assertContains(output, "namespace=io.github.thewisekd.phopencode.core.ui compileSdk=37 minSdk=26")
    }

    @Test
    fun `the feature convention derives its namespace from the path`() {
        printAndroidSettings("feature/chat/ui")
        val output = build.succeeds(":feature:chat:ui:printAndroidSettings", NO_CACHE, sdk = build.emptySdk()).output
        assertContains(output, "namespace=io.github.thewisekd.phopencode.feature.chat.ui compileSdk=37 minSdk=26")
    }

    @Test
    fun `the application convention sets the application id and SDK levels`() {
        build.edit("app/build.gradle.kts") {
            it + """

                val applicationSettings = extensions.getByType<com.android.build.api.dsl.ApplicationExtension>().defaultConfig.run {
                    "applicationId=" + applicationId + " minSdk=" + minSdk + " targetSdk=" + targetSdk
                }
                tasks.register("printApplicationId") {
                    doLast { println(applicationSettings) }
                }
            """.trimIndent() + "\n"
        }
        val output = build.succeeds(":app:printApplicationId", NO_CACHE, sdk = build.emptySdk()).output
        assertContains(output, "applicationId=io.github.thewisekd.phopencode minSdk=26 targetSdk=37")
    }

    /** A direct edge: navigation or Koin could bring the library in transitively and hide a missing one. */
    @Test
    fun `the serialization convention gives an application the runtime`() {
        build.edit("app/build.gradle.kts") {
            it.replace("plugins {\n", "plugins {\n    id(\"phopencode.serialization\")\n")
        }
        val output = build.succeeds(
            ":app:dependencies",
            "--configuration",
            "debugCompileClasspath",
            sdk = build.emptySdk(),
        ).output
        val directEdge = Regex("""(?m)^[+\\]--- org\.jetbrains\.kotlinx:kotlinx-serialization-json:1\.12\.0""")
        assertTrue(directEdge.containsMatchIn(output), "no direct kotlinx-serialization-json edge:\n$output")
    }

    @Test
    fun `the feature convention wires its logic module, the shared UI and navigation`() {
        val output = build.succeeds(
            ":feature:chat:ui:dependencies",
            "--configuration",
            "debugRuntimeClasspath",
            sdk = build.emptySdk(),
        ).output
        assertContains(output, "project ':feature:chat:logic'")
        assertContains(output, "project ':core:ui'")
        assertContains(output, "project ':core:navigation'")
    }

    /** A direct `implementation` edge, so a feature screen can call `MarkdownView` without a module-level line. */
    @Test
    fun `the feature convention puts the shared Markdown view on the compile classpath`() {
        val output = build.succeeds(
            ":feature:chat:ui:dependencies",
            "--configuration",
            "debugCompileClasspath",
            sdk = build.emptySdk(),
        ).output
        val directEdge = Regex("""(?m)^[+\\]--- project '?:core:markdown'?(\s|$)""")
        assertTrue(directEdge.containsMatchIn(output), "no direct :core:markdown edge:\n$output")
    }

    private fun printUnitTestJvmArgs(module: String) {
        build.edit("$module/build.gradle.kts") {
            it + """

                val unitTestJvmArgs = provider { tasks.named<Test>("testDebugUnitTest").get().jvmArgs }
                tasks.register("printUnitTestJvmArgs") {
                    doLast { println("unitTestJvmArgs=" + unitTestJvmArgs.get()) }
                }
            """.trimIndent() + "\n"
        }
    }

    /**
     * Robolectric's FileDescriptorInterceptor reaches into java.base on API 36; see
     * ROBOLECTRIC_JVM_ARGS. The java.io flag comes from AGP and is checked so a change there shows up.
     */
    @Test
    fun `Android unit test JVMs get the module access Robolectric needs`() {
        printUnitTestJvmArgs("core/ui")
        printUnitTestJvmArgs("app")
        val output = build.succeeds(
            ":core:ui:printUnitTestJvmArgs",
            ":app:printUnitTestJvmArgs",
            NO_CACHE,
            sdk = build.emptySdk(),
        ).output
        val lines = output.lines().filter { it.startsWith("unitTestJvmArgs=") }
        assertEquals(2, lines.size, "expected one line per module:\n$output")
        lines.forEach { line ->
            assertContains(line, "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            assertContains(line, "--add-opens=java.base/java.io=ALL-UNNAMED")
        }
    }

    private companion object {
        /** The print tasks close over their script, which the configuration cache cannot store. */
        const val NO_CACHE = "--no-configuration-cache"
    }

    /** Every Android library of the catalog in one app: a conflict or a missing artifact shows up as FAILED. */
    @Test
    fun `the Android libraries resolve together`() {
        build.edit("app/build.gradle.kts") {
            it + """

                dependencies {
                    implementation(libs.androidx.appcompat)
                    implementation(libs.androidx.core.splashscreen)
                    implementation(libs.androidx.datastore)
                    implementation(libs.androidx.datastore.preferences)
                    implementation(libs.tink.android)
                    implementation(libs.markdown.renderer.m3)
                    implementation(libs.markdown.renderer.code)
                    implementation(libs.highlights)
                    implementation(libs.sora.editor)
                    implementation(libs.sora.editor.language.textmate)
                    implementation(libs.coil.compose)
                    implementation(libs.compose.material.icons.extended)
                    implementation(libs.bouncycastle.prov)
                    testImplementation(libs.robolectric)
                }
            """.trimIndent() + "\n"
        }
        val output = build.succeeds(
            ":app:dependencies",
            "--configuration",
            "debugRuntimeClasspath",
            sdk = build.emptySdk(),
        ).output
        assertContains(output, "io.github.rosemoe:editor:0.24.7")
        assertContains(output, "com.mikepenz:multiplatform-markdown-renderer-m3:0.46.0")
        assertContains(output, "androidx.compose.material3:material3")
        assertFalse(" FAILED" in output, "an edge did not resolve:\n$output")
    }
}
