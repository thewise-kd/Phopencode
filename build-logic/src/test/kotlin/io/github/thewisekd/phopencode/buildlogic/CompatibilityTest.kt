package io.github.thewisekd.phopencode.buildlogic

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * "Their compatibility with each other is tried in P0.4": the planned versions are
 * compiled and exercised on the JVM, and the JVM dependency graphs stay Android-free.
 */
@Tag("functional")
class CompatibilityTest {
    private val build = BuildCopy

    @AfterEach
    fun restore() = build.restore()

    @Test
    fun `the JVM libraries work together in one test`() {
        val smoke = assertNotNull(javaClass.getResource("/smoke/JvmLibrariesSmokeTest.kt")).readText()
        build.add("core/api-http/src/test/kotlin/JvmLibrariesSmokeTest.kt", smoke)
        build.edit("core/api-http/build.gradle.kts") {
            it + """

                dependencies {
                    testImplementation(libs.sshj)
                    testImplementation(libs.mina.sshd.core)
                    testImplementation(libs.mina.sshd.sftp)
                    testImplementation(libs.koin.core)
                    testImplementation(libs.koin.test)
                    testImplementation(libs.okhttp.mockwebserver3.junit5)
                    testImplementation(libs.jsch)
                }
            """.trimIndent() + "\n"
        }
        val result = build.succeeds(":core:api-http:test", "--no-build-cache")
        assertEquals(TaskOutcome.SUCCESS, result.task(":core:api-http:test")?.outcome)
    }
}
