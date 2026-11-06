package io.github.thewisekd.phopencode.core.git.parse

import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every prefix and many random corruptions of real output either parse or fail with
 * [GitParseException]: no other exception, no hang.
 */
class RobustnessTest {
    @TempDir
    lateinit var root: File

    private class Sample(val name: String, val bytes: ByteArray, val parse: (ByteArray) -> Any)

    private fun samples(): List<Sample> {
        val origin = TestRepo.bare(root, "origin.git")
        val repo = TestRepo.init(root)
        repo.write("a b.txt", "1\n2\n3\n")
        repo.write("gone.txt", "x\ny")
        repo.writeBytes("bin", byteArrayOf(0, 1, 2))
        repo.commitAll("base\n\nbody")
        repo.git("remote", "add", "origin", origin.dir.absolutePath)
        repo.git("push", "-q", "-u", "origin", "main")
        repo.write("a b.txt", "1\nzwei\n3\n")
        File(repo.dir, "gone.txt").delete()
        repo.writeBytes("bin", byteArrayOf(0, 9))
        repo.git("mv", "a b.txt", "line\nbreak ç.txt")
        repo.write("untracked -rf", "u\n")
        repo.commitAll("second")
        repo.write("line\nbreak ç.txt", "changed\n")
        repo.write("untracked -rf", "u\n")
        return listOf(
            Sample("status", repo.git(*StatusParser.ARGUMENTS.toTypedArray()), StatusParser::parse),
            Sample("log", repo.git(*LogParser.ARGUMENTS.toTypedArray()), LogParser::parse),
            Sample("branch", repo.git(*BranchParser.ARGUMENTS.toTypedArray()), BranchParser::parse),
            Sample("diff", repo.git(*(DiffParser.ARGUMENTS + listOf("HEAD~1")).toTypedArray()), DiffParser::parse),
        )
    }

    private fun check(sample: Sample, input: ByteArray, what: String) {
        try {
            sample.parse(input)
        } catch (_: GitParseException) {
            // expected for broken input
        } catch (e: Throwable) {
            fail("${sample.name} $what threw ${e::class.simpleName}: ${e.message}")
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun everyPrefixAndRandomCorruptionIsHandled() {
        val all = samples()
        for (sample in all) {
            assertTrue(sample.bytes.isNotEmpty(), sample.name)
            sample.parse(sample.bytes)
            for (n in 0 until sample.bytes.size) check(sample, sample.bytes.copyOf(n), "prefix $n")
            val random = Random(42)
            val alphabet = byteArrayOf(0, '\n'.code.toByte(), ' '.code.toByte(), '@'.code.toByte(), '-'.code.toByte(), '+'.code.toByte(), '"'.code.toByte(), '\\'.code.toByte(), '1'.code.toByte(), 0xFF.toByte())
            repeat(2000) { i ->
                val copy = sample.bytes.copyOf()
                repeat(1 + random.nextInt(3)) {
                    copy[random.nextInt(copy.size)] = if (random.nextBoolean()) alphabet[random.nextInt(alphabet.size)] else random.nextInt(256).toByte()
                }
                check(sample, copy, "mutation $i")
            }
            repeat(200) { i -> check(sample, random.nextBytes(random.nextInt(1, 200)), "noise $i") }
        }
    }
}
