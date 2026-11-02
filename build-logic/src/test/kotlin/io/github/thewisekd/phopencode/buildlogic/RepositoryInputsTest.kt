package io.github.thewisekd.phopencode.buildlogic

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The functional tests run a copy of these files, so a file the copy takes but the
 * test tasks do not declare could change without the tests running again.
 */
class RepositoryInputsTest {
    private val declared: Set<String> =
        assertNotNull(javaClass.getResource("/repository-build-files.txt")).readText().lines().toSet()

    @Test
    fun `every file the copy takes is a declared test input`() {
        assertEquals(emptyList(), Repository.buildFiles().filterNot { it in declared }.sorted())
    }

    @Test
    fun `the copy takes the build scripts of build-logic`() {
        val copied = Repository.buildFiles()
        assertTrue("build-logic/build.gradle.kts" in copied)
        assertTrue("build-logic/settings.gradle.kts" in copied)
    }

    @Test
    fun `no generated file is a declared test input`() {
        val generated = declared.filter { path ->
            listOf("build", ".gradle", ".kotlin").any { directory -> directory in path.split('/').dropLast(1) }
        }
        assertEquals(emptyList(), generated)
    }
}
