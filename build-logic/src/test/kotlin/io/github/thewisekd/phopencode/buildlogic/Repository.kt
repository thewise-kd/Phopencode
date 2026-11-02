package io.github.thewisekd.phopencode.buildlogic

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createDirectories

/** The real checkout, and throwaway copies of its build files. */
object Repository {
    val root: File = File(System.getProperty("phopencode.repositoryRoot"))

    fun file(path: String): File = root.resolve(path)

    /**
     * Copies what defines the build. Android module directories stay behind because
     * P0.5 owns them, and JVM sources stay behind because the tests prove the build
     * rather than the code that later tasks add.
     */
    fun copyBuildTo(target: File) {
        buildFiles().forEach { relative ->
            val destination = target.toPath().resolve(relative)
            destination.parent.createDirectories()
            Files.copy(root.toPath().resolve(relative), destination)
        }
    }

    /** The files [copyBuildTo] copies, relative to the repository root. */
    fun buildFiles(): List<String> {
        val top = root.toPath()
        return Files.walk(top).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .map { top.relativize(it).toString().replace(File.separatorChar, '/') }
                .filter(::isBuildFile)
                .toList()
        }
    }

    private fun isBuildFile(path: String): Boolean = when {
        "/build/" in path || "/.gradle/" in path || "/.kotlin/" in path -> false
        path.startsWith("build-logic/") -> !path.startsWith("build-logic/src/test/")
        path.startsWith("gradle/") -> true
        path in ROOT_FILES -> true
        else -> JVM_BUILD_FILE.matches(path)
    }

    private val ROOT_FILES = setOf(
        "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
    )
    private val ANDROID_CORE = setOf("storage", "ui", "markdown", "codeview")
    private val JVM_BUILD_FILE = Regex("""(core/(?!(${ANDROID_CORE.joinToString("|")})/)[^/]+|feature/[^/]+/logic)/build\.gradle\.kts""")
}
