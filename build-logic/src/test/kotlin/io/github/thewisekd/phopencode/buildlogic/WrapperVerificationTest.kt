package io.github.thewisekd.phopencode.buildlogic

import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** Runs gradle/verify-wrapper.sh against the real wrapper and tampered copies. */
class WrapperVerificationTest {
    @TempDir
    lateinit var copy: File

    private val properties get() = copy.resolve("gradle-wrapper.properties")

    private fun verify(directory: File? = null): Pair<Int, String> {
        val process = ProcessBuilder("sh", Repository.file("gradle/verify-wrapper.sh").path)
            .redirectErrorStream(true)
            .apply { directory?.let { environment()["PHOPENCODE_WRAPPER_DIR"] = it.path } }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "the script hung" }
        return process.exitValue() to output
    }

    private fun tampered(): File {
        Repository.file("gradle/wrapper").copyRecursively(copy, overwrite = true)
        return copy
    }

    @Test
    fun `the committed wrapper passes`() {
        val (exit, output) = verify()
        assertEquals(0, exit, output)
    }

    @Test
    fun `the committed pins are the published Gradle hashes`() {
        assertEquals(
            "3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98",
            Repository.file("gradle/wrapper/gradle-wrapper.jar.sha256").readText().trim(),
        )
        assertContains(
            Repository.file("gradle/wrapper/gradle-wrapper.properties").readText(),
            "distributionSha256Sum=21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607",
        )
    }

    @Test
    fun `a changed jar fails`() {
        tampered().resolve("gradle-wrapper.jar").appendBytes(byteArrayOf(0))
        val (exit, output) = verify(copy)
        assertEquals(1, exit, output)
        assertContains(output, "does not match the pin")
    }

    @Test
    fun `a removed distribution hash fails`() {
        tampered()
        properties.writeText(properties.readLines().filterNot { "distributionSha256Sum" in it }.joinToString("\n"))
        val (exit, output) = verify(copy)
        assertEquals(1, exit, output)
        assertContains(output, "distributionSha256Sum is missing")
    }

    @Test
    fun `a distribution from another host fails`() {
        tampered()
        properties.writeText(properties.readText().replace("services.gradle.org", "example.com"))
        val (exit, output) = verify(copy)
        assertEquals(1, exit, output)
        assertContains(output, "not an official")
    }

    /**
     * java.util.Properties, which the wrapper uses, reads each of these differently
     * from a key=value scan, so the script must refuse the file rather than guess.
     */
    @ParameterizedTest
    @MethodSource("filesTheWrapperWouldReadDifferently")
    fun `a file the script cannot read as the wrapper does fails`(change: (String) -> String) {
        tampered()
        properties.writeText(change(properties.readText()))
        val (exit, output) = verify(copy)
        assertEquals(1, exit, output)
    }

    @ParameterizedTest
    @MethodSource("requiredKeys")
    fun `a missing required key fails`(key: String) {
        tampered()
        properties.writeText(properties.readLines().filterNot { it.startsWith("$key=") }.joinToString("\n"))
        val (exit, output) = verify(copy)
        assertEquals(1, exit, output)
        assertContains(output, key)
    }

    @Test
    fun `comments and blank lines still pass`() {
        tampered()
        properties.writeText("# generated\n\n! note, not a key=value\n#distributionUrl=ignored\n" + properties.readText())
        val (exit, output) = verify(copy)
        assertEquals(0, exit, output)
    }

    @Test
    fun `turning off URL validation fails`() {
        tampered()
        properties.writeText(properties.readText().replace("validateDistributionUrl=true", "validateDistributionUrl=false"))
        val (exit, output) = verify(copy)
        assertEquals(1, exit, output)
        assertContains(output, "validateDistributionUrl")
    }

    private companion object {
        const val FOREIGN_URL = """https\://example.com/gradle-9.8.2-bin.zip"""
        const val OTHER_SUM = "0000000000000000000000000000000000000000000000000000000000000000"
        const val REAL_URL = """https\://services.gradle.org/distributions/gradle-9.8.2-bin.zip"""

        private fun case(name: String, change: (String) -> String): Arguments = arguments(named(name, change))

        private fun appended(line: String): (String) -> String = { it.trimEnd() + "\n" + line + "\n" }

        @JvmStatic
        fun requiredKeys() = listOf("distributionUrl", "distributionSha256Sum", "validateDistributionUrl")

        @JvmStatic
        fun filesTheWrapperWouldReadDifferently(): List<Arguments> {
            val duplicates = listOf(
                case("a second distributionUrl", appended("distributionUrl=$FOREIGN_URL")),
                case("a second distributionSha256Sum", appended("distributionSha256Sum=$OTHER_SUM")),
                case("a second validateDistributionUrl", appended("validateDistributionUrl=false")),
                case("a duplicate before the real value") { "distributionUrl=$REAL_URL\n$it" },
                case("a second, identical value", appended("distributionUrl=$REAL_URL")),
                case("a second value of an optional key", appended("zipStorePath=elsewhere")),
            )
            val separators = listOf(" = ", ":", " ", " : ", "\t").flatMap { separator ->
                listOf(
                    "distributionUrl" to FOREIGN_URL,
                    "distributionSha256Sum" to OTHER_SUM,
                    "validateDistributionUrl" to "false",
                ).map { (key, value) ->
                    case("$key with separator '${separator.replace("\t", "tab")}'", appended("$key$separator$value"))
                }
            }
            val continuations = listOf(
                case("a continued URL") { it.replace("$REAL_URL\n", "$REAL_URL\\\n.example.com\n") },
                case("a key split over two lines", appended("distribution\\\nUrl=$FOREIGN_URL")),
                case("a continuation that swallows the next line") {
                    it.replace("zipStorePath=wrapper/dists", "zipStorePath=wrapper/dists\\")
                },
                case("a continuation into something that looks like a comment") {
                    it.trimEnd() + "\\\n# not a comment here\n"
                },
            )
            val whitespace = listOf(
                case("an indented key", appended("  distributionUrl=$FOREIGN_URL")),
                case("a key behind a form feed", appended("\u000CdistributionUrl=$FOREIGN_URL")),
                case("a key behind a bare CR in a comment", appended("# note\rdistributionUrl=$FOREIGN_URL")),
                case("CRLF line endings") { it.replace("\n", "\r\n") },
                case("a NUL byte in a comment", appended("# \u0000")),
                case("a line of spaces", appended("   ")),
                case("a value with trailing space") {
                    it.replace("validateDistributionUrl=true", "validateDistributionUrl=true ")
                },
            )
            val other = listOf(
                case("an unknown key", appended("somethingElse=value")),
                case("a line with no separator", appended("distributionUrl")),
                case("an escape in a value that strips back to the real one") {
                    it.replace("services.gradle.org", """services.\gradle.org""")
                },
                case("a unicode escape in a value", appended("""zipStorePath=\u0041""")),
                case("a backslash in the key", appended("""distribution\Url=$FOREIGN_URL""")),
                case("a URL on the right host with a query") { it.replace("-bin.zip", "-bin.zip?x=1") },
                case("a version that is not a version") { it.replace("gradle-9.8.2-bin", "gradle-9..2.-bin") },
            )
            return duplicates + separators + continuations + whitespace + other
        }
    }
}
