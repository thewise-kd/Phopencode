package io.github.thewisekd.phopencode.feature.git.ui.status

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How a repository path splits into the name and the folder a row shows. Plain functions, but run
 * by Robolectric so the module's SDK-filtered test runs pick them up too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class StatusPathsTest {
    @Test
    fun `a nested file splits at its last slash`() {
        assertEquals("Hazır.kt", fileName("src/main/Hazır.kt"))
        assertEquals("src/main", folderOf("src/main/Hazır.kt"))
    }

    @Test
    fun `a file at the repository's root has no folder`() {
        assertEquals("README.md", fileName("README.md"))
        assertNull(folderOf("README.md"))
    }

    @Test
    fun `an untracked folder keeps its trailing slash in its name`() {
        assertEquals("build/", fileName("out/build/"))
        assertEquals("out", folderOf("out/build/"))
        assertEquals("build/", fileName("build/"))
        assertNull(folderOf("build/"))
    }

    @Test
    fun `spaces and newlines stay in the name`() {
        assertEquals("todo list\n.txt", fileName("notes/todo list\n.txt"))
        assertEquals("notes", folderOf("notes/todo list\n.txt"))
    }

    @Test
    fun `a short commit is its first seven characters`() {
        assertEquals("0123456", shortCommit(HASH))
        assertEquals("abc", shortCommit("abc"))
    }
}
