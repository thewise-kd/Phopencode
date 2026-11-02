package io.github.thewisekd.phopencode.buildlogic

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files

/**
 * A throwaway copy of the repository's real build, run through TestKit.
 *
 * Tests edit the copy with [edit] and [add]; [restore] undoes those edits. Tests
 * share one copy because compiling its build-logic takes minutes on the Pi, and
 * JUnit runs them one at a time.
 */
object BuildCopy {
    val directory: File = Files.createTempDirectory("phopencode-build").toFile().also { copy ->
        Repository.copyBuildTo(copy)
        Runtime.getRuntime().addShutdownHook(Thread { copy.deleteRecursively() })
    }

    private val SDK_VARIABLES = setOf("ANDROID_HOME", "ANDROID_SDK_ROOT")

    private val originals = mutableMapOf<File, String?>()

    fun add(path: String, content: String) = edit(path) { content }

    fun edit(path: String, change: (String) -> String) {
        val file = directory.resolve(path)
        val before = if (file.exists()) file.readText() else null
        // Not putIfAbsent: that overwrites the null recorded for a file the test created.
        if (file !in originals) {
            originals[file] = before
        }
        file.parentFile.mkdirs()
        file.writeText(change(before.orEmpty()))
    }

    fun restore() {
        originals.forEach { (file, content) ->
            if (content == null) file.delete() else file.writeText(content)
        }
        originals.clear()
    }

    /**
     * An SDK directory that holds nothing: enough for the module map to count it as
     * present. Gradle refuses to include a project without a directory, and the
     * copy leaves the Android modules out, so their directories are created here.
     */
    fun emptySdk(): File {
        ModuleMap.android.forEach { directory.resolve(it.removePrefix(":").replace(':', '/')).mkdirs() }
        return directory.resolve("fake-sdk").apply { mkdirs() }
    }

    fun runner(vararg arguments: String, sdk: File? = null): GradleRunner {
        val environment = System.getenv().filterKeys { it !in SDK_VARIABLES }.toMutableMap()
        sdk?.let { environment["ANDROID_HOME"] = it.path }
        return GradleRunner.create()
            .withProjectDir(directory)
            .withTestKitDir(File(System.getProperty("phopencode.testKitDir")))
            .withEnvironment(environment)
            .withArguments(*arguments, "--stacktrace")
            .forwardOutput()
    }

    fun succeeds(vararg arguments: String, sdk: File? = null): BuildResult =
        runner(*arguments, sdk = sdk).build()

    fun fails(vararg arguments: String, sdk: File? = null): BuildResult =
        runner(*arguments, sdk = sdk).buildAndFail()
}
