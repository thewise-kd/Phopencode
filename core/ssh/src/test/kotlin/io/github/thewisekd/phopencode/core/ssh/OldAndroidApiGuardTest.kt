package io.github.thewisekd.phopencode.core.ssh

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * minSdk is 26: `java.util.HexFormat` exists on Android only from API 34, yet compiles here because
 * it is a Java 17 API. This scan keeps it out of the main sources.
 */
class OldAndroidApiGuardTest {
    @Test
    fun mainSourcesDoNotUseHexFormat() {
        val main = moduleDir().resolve("src/main")
        val sources = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "no Kotlin sources found under ${main.path}")
        val hits = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (FORBIDDEN.containsMatchIn(line)) "${file.path}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue(hits.isEmpty(), "java.util.HexFormat is missing below Android 14:\n" + hits.joinToString("\n"))
    }

    /** The `core/ssh` directory, whether Gradle runs the test there or from a parent directory. */
    private fun moduleDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (candidate in listOf(dir, dir.resolve("core/ssh"))) {
                if (candidate.resolve(MARKER).isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("core/ssh module directory not found from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val MARKER = "src/main/kotlin/io/github/thewisekd/phopencode/core/ssh"
        val FORBIDDEN = Regex("""\bHexFormat\b""")
    }
}
