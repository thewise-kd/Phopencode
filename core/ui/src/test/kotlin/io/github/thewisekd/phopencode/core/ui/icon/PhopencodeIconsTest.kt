package io.github.thewisekd.phopencode.core.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhopencodeIconsTest {
    /** Every icon the feature screens rely on, by name, so a removed one fails to compile here. */
    private val named: Map<String, ImageVector> = with(PhopencodeIcons) {
        mapOf(
            "Send" to Send,
            "Stop" to Stop,
            "Add" to Add,
            "Folder" to Folder,
            "FolderOpen" to FolderOpen,
            "Git" to Git,
            "Terminal" to Terminal,
            "Chat" to Chat,
            "Server" to Server,
            "Key" to Key,
            "Settings" to Settings,
            "MoreVert" to MoreVert,
            "Refresh" to Refresh,
            "Sparkle" to Sparkle,
            "Agent" to Agent,
            "Attach" to Attach,
            "File" to File,
            "Check" to Check,
            "Close" to Close,
            "ChevronRight" to ChevronRight,
            "Edit" to Edit,
            "History" to History,
            "Search" to Search,
            "Warning" to Warning,
            "Info" to Info,
        )
    }

    /** Every icon of the set, read by reflection so that one added later is checked too. */
    private val all: Map<String, ImageVector> = PhopencodeIcons::class.java.methods
        .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == ImageVector::class.java }
        .associate { it.name.removePrefix("get") to it.invoke(PhopencodeIcons) as ImageVector }

    private fun VectorNode.paths(): List<VectorPath> = when (this) {
        is VectorPath -> listOf(this)
        is VectorGroup -> flatMap { it.paths() }
    }

    @Test
    fun `the reflection sees every named icon`() {
        assertTrue(all.keys.containsAll(named.keys), "missing: ${named.keys - all.keys}")
    }

    @Test
    fun `every icon is a 24 dp square on a 24 unit viewport`() {
        all.forEach { (name, icon) ->
            assertEquals(24.dp, icon.defaultWidth, name)
            assertEquals(24.dp, icon.defaultHeight, name)
            assertEquals(24f, icon.viewportWidth, name)
            assertEquals(24f, icon.viewportHeight, name)
        }
    }

    @Test
    fun `every icon draws a non-empty path`() {
        all.forEach { (name, icon) ->
            val paths = icon.root.paths()
            assertTrue(paths.isNotEmpty(), "$name has no path")
            assertTrue(paths.all { it.pathData.isNotEmpty() }, "$name has an empty path")
        }
    }

    @Test
    fun `no two names share a picture, so each action looks distinct`() {
        val byPicture = named.entries.groupBy({ it.value.root.paths().map { p -> p.pathData } }, { it.key })
        assertEquals(emptyList(), byPicture.values.filter { it.size > 1 })
    }

    @Test
    fun `an icon is built once and reused`() {
        assertTrue(PhopencodeIcons.Git === PhopencodeIcons.Git)
        assertTrue(PhopencodeIcons.Send === PhopencodeIcons.Send)
    }

    @Test
    fun `the chevron follows the layout direction`() {
        assertTrue(PhopencodeIcons.ChevronRight.autoMirror)
    }
}
