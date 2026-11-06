package io.github.thewisekd.phopencode.core.git.parse

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BranchParserTest {
    @TempDir
    lateinit var root: File

    private fun TestRepo.branches(): List<GitBranch> =
        BranchParser.parse(git(*BranchParser.ARGUMENTS.toTypedArray()))

    @Test
    fun initialRepositoryHasNoBranches() {
        assertEquals(emptyList(), TestRepo.init(root).branches())
    }

    @Test
    fun localAndRemoteBranchesWithTracking() {
        val origin = TestRepo.bare(root, "origin.git")
        val seed = TestRepo.init(root, "seed")
        seed.commitAll("one")
        seed.git("remote", "add", "origin", origin.dir.absolutePath)
        seed.git("push", "-q", "origin", "main")
        seed.git("push", "-q", "origin", "main:özellik/çalışma-\$(x)")
        seed.git("push", "-q", "origin", "main:to-delete")
        val repo = TestRepo.clone(root, origin, "work")
        repo.git("checkout", "-q", "-b", "local-only")
        repo.git("checkout", "-q", "-b", "tracking", "origin/özellik/çalışma-\$(x)")
        repo.git("checkout", "-q", "-b", "gone", "origin/to-delete")
        repo.git("checkout", "-q", "main")
        seed.commitAll("two")
        seed.git("push", "-q", "origin", "main")
        seed.git("push", "-q", "origin", "--delete", "to-delete")
        repo.git("fetch", "-q", "--prune")
        repo.commitAll("local one")
        repo.commitAll("local two")

        val byName = repo.branches().associateBy { it.name }
        val main = byName.getValue("main")
        assertEquals(BranchKind.LOCAL, main.kind)
        assertTrue(main.isCurrent)
        assertEquals("refs/heads/main", main.refName)
        assertEquals(repo.head(), main.hash)
        assertEquals("refs/remotes/origin/main", main.upstream)
        assertEquals("origin/main", main.upstreamName)
        assertEquals(UpstreamState.Tracking(ahead = 2, behind = 1), main.upstreamState)

        val local = byName.getValue("local-only")
        assertFalse(local.isCurrent)
        assertNull(local.upstream)
        assertNull(local.upstreamState)

        val tracking = byName.getValue("tracking")
        assertEquals("origin/özellik/çalışma-\$(x)", tracking.upstreamName)
        assertEquals(UpstreamState.Tracking(0, 0), tracking.upstreamState)

        assertEquals(UpstreamState.Gone, byName.getValue("gone").upstreamState)

        val remoteMain = byName.getValue("origin/main")
        assertEquals(BranchKind.REMOTE, remoteMain.kind)
        assertFalse(remoteMain.isCurrent)
        assertNull(remoteMain.upstream)
        assertEquals(BranchKind.REMOTE, byName.getValue("origin/özellik/çalışma-\$(x)").kind)
        assertFalse("origin/to-delete" in byName)

        val remoteHead = byName.getValue("origin/HEAD")
        assertTrue(remoteHead.isSymbolic)
        assertEquals("refs/remotes/origin/main", remoteHead.symrefTarget)
        assertNull(main.symrefTarget)
    }

    @Test
    fun detachedHeadMarksNoBranchCurrent() {
        val repo = TestRepo.init(root)
        repo.commitAll("one")
        repo.git("checkout", "-q", "--detach")
        assertFalse(repo.branches().single().isCurrent)
    }

    @Test
    fun fixedSamplesOfTrackWords() {
        val h = "e".repeat(40)
        fun line(track: String) = "refs/heads/x\u0000 \u0000$h\u0000refs/remotes/o/x\u0000$track\u0000\n"
        assertEquals(UpstreamState.Tracking(3, 0), BranchParser.parse(bytes(line("ahead 3"))).single().upstreamState)
        assertEquals(UpstreamState.Tracking(0, 4), BranchParser.parse(bytes(line("behind 4"))).single().upstreamState)
        assertEquals(UpstreamState.Tracking(5, 6), BranchParser.parse(bytes(line("ahead 5, behind 6"))).single().upstreamState)
    }

    @Test
    fun malformedInputFailsWithAParseError() {
        val h = "e".repeat(40)
        val bad = listOf(
            "refs/heads/x\u0000 \u0000$h\u0000\u0000\u0000",
            "refs/heads/x\u0000 \u0000$h\u0000\u0000\u0000\u0000\n",
            "refs/heads/x\u0000 \u0000$h\u0000\u0000\n",
            "refs/heads/x\u0000+\u0000$h\u0000\u0000\u0000\n",
            "refs/heads/x\u0000 \u0000nothash\u0000\u0000\u0000\n",
            "refs/tags/x\u0000 \u0000$h\u0000\u0000\u0000\n",
            "\u0000 \u0000$h\u0000\u0000\u0000\n",
            "refs/heads/x\u0000 \u0000$h\u0000refs/remotes/o/x\u0000sideways 2\u0000\n",
            "refs/heads/x\u0000 \u0000$h\u0000refs/remotes/o/x\u0000ahead -2\u0000\n",
            "refs/heads/x\u0000 \u0000$h\u0000\u0000ahead 2\u0000\n",
            "\n",
        )
        for (sample in bad) {
            assertFailsWith<GitParseException>(sample.replace("\u0000", "\\0")) { BranchParser.parse(bytes(sample)) }
        }
    }

    @Test
    fun toStringHidesNames() {
        val b = GitBranch("refs/heads/secret", BranchKind.LOCAL, true, "e".repeat(40), "refs/remotes/o/secret", UpstreamState.Gone, null)
        assertFalse(b.toString().contains("secret"))
    }
}
