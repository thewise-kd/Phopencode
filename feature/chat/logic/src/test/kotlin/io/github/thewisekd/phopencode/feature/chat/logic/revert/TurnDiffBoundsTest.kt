package io.github.thewisekd.phopencode.feature.chat.logic.revert

import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TurnDiffBoundsTest {
    private fun diff(path: String, patch: String?) = FileDiff(VcsFile(path, VcsFileStatus.Modified, 3, 2), patch)

    @Test
    fun `a small diff is kept as it is`() {
        val diffs = listOf(diff("a", "+1"), diff("b", null))

        assertSame(diffs[0], TurnDiffBounds.bound(diffs)[0])
        assertEquals(diffs, TurnDiffBounds.bound(diffs))
    }

    @Test
    fun `a patch over the per-file limit is dropped, keeping the file and its counts`() {
        val atLimit = diff("a", "x".repeat(TurnDiffBounds.MAX_PATCH_CHARS))
        val over = diff("b", "x".repeat(TurnDiffBounds.MAX_PATCH_CHARS + 1))

        val bounded = TurnDiffBounds.bound(listOf(atLimit, over))

        assertEquals(atLimit, bounded[0])
        assertEquals(over.file, bounded[1].file)
        assertNull(bounded[1].patch)
    }

    @Test
    fun `patches past the total budget are dropped`() {
        val each = TurnDiffBounds.MAX_PATCH_CHARS
        val count = TurnDiffBounds.MAX_TOTAL_PATCH_CHARS / each
        val fits = List(count) { diff("f$it", "x".repeat(each)) }
        val past = diff("last", "x")
        val small = diff("tiny", "")

        val bounded = TurnDiffBounds.bound(fits + past + small)

        assertEquals(fits, bounded.take(count))
        assertNull(bounded[count].patch)
        assertEquals("", bounded[count + 1].patch)
    }

    @Test
    fun `files past the count limit are left out`() {
        val diffs = List(TurnDiffBounds.MAX_FILES + 1) { diff("f$it", null) }

        val bounded = TurnDiffBounds.bound(diffs)

        assertEquals(diffs.take(TurnDiffBounds.MAX_FILES), bounded)
    }
}
