package io.github.thewisekd.phopencode.buildlogic

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The acceptance tests of P0.4 against a copy of the real build: the JVM modules
 * check without an SDK, Android modules are left out, and each way of giving a JVM
 * module an Android dependency fails its build.
 */
@Tag("functional")
class JvmModuleRulesTest {
    private val build = BuildCopy

    @AfterEach
    fun restore() = build.restore()

    private fun addDependency(notation: String, module: String = "core/model", configuration: String = "implementation") {
        build.edit("$module/build.gradle.kts") { it + "\ndependencies {\n    $configuration($notation)\n}\n" }
    }

    /** A local Maven repository, so a transitive case needs no real library that misbehaves. */
    private fun publish(name: String, packaging: String = "jar", dependency: String = "", artifact: Boolean = true) {
        val directory = "fixture-repo/org/fixture/$name/1.0"
        build.add(
            "$directory/$name-1.0.pom",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.fixture</groupId>
              <artifactId>$name</artifactId>
              <version>1.0</version>
              <packaging>$packaging</packaging>
              <dependencies>$dependency</dependencies>
            </project>
            """.trimIndent(),
        )
        val extension = if (packaging == "aar") "aar" else "jar"
        if (artifact) {
            build.add("$directory/$name-1.0.$extension", "not read by these tests")
        }
        build.edit("settings.gradle.kts") {
            if ("fixture-repo" in it) {
                it
            } else {
                it + "\ndependencyResolutionManagement {\n" +
                    "    repositories {\n        maven(url = uri(\"fixture-repo\"))\n    }\n}\n"
            }
        }
    }

    @Test
    fun `jvmCheck passes without an SDK, runs every JVM module and verifies the wrapper`() {
        val result = build.succeeds("jvmCheck")
        ModuleMap.jvm.forEach { module ->
            assertTrue(result.task("$module:jvmCheck") != null, "$module:jvmCheck did not run")
            assertTrue(result.task("$module:check") != null, "$module:check did not run")
            assertTrue(result.task("$module:verifyNoAndroidDependencies") != null, "$module was not verified")
        }
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyWrapper")?.outcome)
    }

    @Test
    fun `a wrapper that does not match its pin fails jvmCheck`() {
        build.add("gradle/wrapper/gradle-wrapper.jar.sha256", "0".repeat(64) + "\n")
        val result = build.fails("jvmCheck")
        assertEquals(TaskOutcome.FAILED, result.task(":verifyWrapper")?.outcome)
        assertContains(result.output, "does not match the pin")
    }

    @Test
    fun `without an SDK only the JVM modules are projects`() {
        val output = build.succeeds("projects").output
        ModuleMap.jvm.forEach { assertContains(output, "'$it'") }
        ModuleMap.android.forEach { assertFalse("'$it'" in output, "$it is included without an SDK:\n$output") }
    }

    @Test
    fun `with an SDK all 32 modules are projects`() {
        val output = build.succeeds("projects", sdk = build.emptySdk()).output
        assertEquals(32, ModuleMap.jvm.size + ModuleMap.android.size)
        (ModuleMap.jvm + ModuleMap.android).forEach { assertContains(output, "'$it'") }
    }

    @Test
    fun `an SDK in local properties counts and a missing directory does not`() {
        build.add("local.properties", "sdk.dir=${build.emptySdk().path}\n")
        assertContains(build.succeeds("projects").output, "':app'")

        build.add("local.properties", "sdk.dir=${build.directory.resolve("no-such-sdk").path}\n")
        assertFalse("':app'" in build.succeeds("projects").output)
    }

    @Test
    fun `a failing test in a JVM module fails jvmCheck`() {
        build.add(
            "core/model/src/test/kotlin/FailingTest.kt",
            """
            import org.junit.jupiter.api.Test
            import kotlin.test.fail

            class FailingTest {
                @Test
                fun fails(): Unit = fail("proves that tests run")
            }
            """.trimIndent(),
        )
        val result = build.fails("jvmCheck")
        assertEquals(TaskOutcome.FAILED, result.task(":core:model:test")?.outcome)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "\"androidx.core:core-ktx:1.19.1\"",
            "\"androidx.annotation:annotation:1.9.1\"",
            "libs.tink.android",
            "libs.koin.android",
            "libs.kotlinx.coroutines.android",
            "libs.sora.editor",
            "libs.coil.compose",
        ],
    )
    fun `a direct Android dependency fails the compile of a JVM module`(notation: String) {
        addDependency(notation)
        val result = build.fails(":core:model:compileKotlin")
        assertContains(result.output, "must stay free of Android")
        assertNotEquals(TaskOutcome.SUCCESS, result.task(":core:model:compileKotlin")?.outcome)
    }

    @ParameterizedTest
    @ValueSource(strings = ["runtimeOnly", "testRuntimeOnly"])
    fun `a dependency that never reaches a compile classpath still fails the compile`(configuration: String) {
        addDependency("libs.tink.android", configuration = configuration)
        val result = build.fails(":core:model:compileKotlin")
        assertContains(result.output, "must stay free of Android")
        assertContains(result.output, "tink-android")
        assertNotEquals(TaskOutcome.SUCCESS, result.task(":core:model:compileKotlin")?.outcome)
    }

    @Test
    fun `a transitive Android dependency on the runtime classpath only fails the compile`() {
        publish(
            "runtime-innocent",
            dependency = "<dependency><groupId>androidx.core</groupId><artifactId>core</artifactId>" +
                "<version>1.19.1</version><scope>runtime</scope></dependency>",
        )
        addDependency("\"org.fixture:runtime-innocent:1.0\"")
        val output = build.fails(":core:model:compileKotlin").output
        assertContains(output, "must stay free of Android")
        assertContains(output, "androidx.core:core")
    }

    @Test
    fun `a runtime dependency on an Android module fails the compile`() {
        addDependency("project(\":core:ui\")", configuration = "runtimeOnly")
        val output = build.fails(":core:model:compileKotlin", sdk = build.emptySdk()).output
        assertContains(output, "must stay free of Android")
        assertContains(output, ":core:ui")
    }

    /** The guard cannot vouch for a graph it cannot see, but the cause is not Android. */
    @Test
    fun `an ordinary resolution failure fails the check without blaming Android`() {
        publish("present")
        addDependency("\"org.fixture:absent:1.0\"", configuration = "runtimeOnly")
        val result = build.fails(":core:model:compileKotlin")
        assertContains(result.output, "org.fixture:absent:1.0")
        assertFalse("must stay free of Android" in result.output, result.output)
        assertNotEquals(TaskOutcome.SUCCESS, result.task(":core:model:compileKotlin")?.outcome)
    }

    @Test
    fun `a missing artifact fails the check without blaming Android`() {
        publish("hollow", artifact = false)
        addDependency("\"org.fixture:hollow:1.0\"", configuration = "runtimeOnly")
        val result = build.fails(":core:model:compileKotlin")
        assertContains(result.output, "hollow")
        assertFalse("must stay free of Android" in result.output, result.output)
        assertNotEquals(TaskOutcome.SUCCESS, result.task(":core:model:compileKotlin")?.outcome)
    }

    @Test
    fun `an Android test dependency fails too`() {
        build.edit("core/model/build.gradle.kts") {
            it + "\ndependencies {\n    testImplementation(libs.robolectric)\n}\n"
        }
        assertContains(build.fails(":core:model:test").output, "must stay free of Android")
    }

    @Test
    fun `a transitive Android dependency fails`() {
        publish(
            "innocent",
            dependency = "<dependency><groupId>androidx.core</groupId><artifactId>core</artifactId>" +
                "<version>1.19.1</version></dependency>",
        )
        addDependency("\"org.fixture:innocent:1.0\"")
        val output = build.fails(":core:model:compileKotlin").output
        assertContains(output, "must stay free of Android")
        assertContains(output, "androidx.core:core")
    }

    @Test
    fun `an AAR from an unknown group fails`() {
        publish("mystery", packaging = "aar")
        addDependency("\"org.fixture:mystery:1.0\"")
        val output = build.fails(":core:model:compileKotlin").output
        assertContains(output, "must stay free of Android")
        assertContains(output, "mystery-1.0.aar")
    }

    @Test
    fun `an AAR file dependency fails`() {
        build.add("core/model/libs/local.aar", "not read by these tests")
        addDependency("files(\"libs/local.aar\")")
        val output = build.fails(":core:model:compileKotlin").output
        assertContains(output, "must stay free of Android")
        assertContains(output, "local.aar")
    }

    @Test
    fun `an Android module does not exist for a JVM module to depend on without an SDK`() {
        addDependency("project(\":core:ui\")")
        assertContains(build.fails(":core:model:compileKotlin").output, "':core:ui' could not be found")
    }

    @Test
    fun `a dependency on an Android module fails with an SDK`() {
        addDependency("project(\":core:ui\")")
        val output = build.fails(":core:model:compileKotlin", sdk = build.emptySdk()).output
        assertContains(output, "must stay free of Android")
        assertContains(output, ":core:ui")
    }

    @Test
    fun `a transitive dependency on an Android module fails`() {
        addDependency("project(\":core:ui\")", module = "core/navigation")
        addDependency("project(\":core:navigation\")")
        val output = build.fails(":core:model:compileKotlin", sdk = build.emptySdk()).output
        assertContains(output, "must stay free of Android")
    }

    @ParameterizedTest
    @ValueSource(strings = ["com.android.library", "com.android.application", "org.jetbrains.kotlin.plugin.compose"])
    fun `a JVM module cannot apply an Android plugin`(plugin: String) {
        build.edit("core/model/build.gradle.kts") {
            it.replace("plugins {\n", "plugins {\n    id(\"$plugin\")\n")
        }
        assertContains(build.fails("help", sdk = build.emptySdk()).output, "must stay free of Android")
    }

    @Test
    fun `a JVM module cannot leave the convention plugin`() {
        build.add("core/model/build.gradle.kts", "plugins {\n    `java-library`\n}\n")
        assertContains(build.fails("help").output, "must apply phopencode.jvm.library")
    }

    @Test
    fun `a JVM module compiles against the Java 17 API, not the JDK that runs Gradle`() {
        build.add(
            "core/model/src/main/kotlin/Jdk21Only.kt",
            "val newerThanAndroidSees = java.util.SequencedCollection::class\n",
        )
        val result = build.fails(":core:model:compileKotlin")
        assertContains(result.output, "SequencedCollection")
        assertEquals(TaskOutcome.FAILED, result.task(":core:model:compileKotlin")?.outcome)
    }
}
