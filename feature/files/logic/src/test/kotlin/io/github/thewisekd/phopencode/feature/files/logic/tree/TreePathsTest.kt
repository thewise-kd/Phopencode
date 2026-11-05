package io.github.thewisekd.phopencode.feature.files.logic.tree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TreePathsTest {
    private val paths = TreePaths("/srv/proje/")

    @Test
    fun `the root is normalized`() {
        assertEquals("/srv/proje", paths.root)
        assertEquals("/a/c", TreePaths.normalize("//a/./b/../c/"))
        assertNull(TreePaths.normalize("/.."))
        assertNull(TreePaths.normalize("relative"))
    }

    @Test
    fun `paths resolve inside the root only`() {
        assertEquals("/srv/proje/src", paths.resolve("src"))
        assertEquals("/srv/proje/src", paths.resolve("/srv/proje/./src/"))
        assertEquals("/srv/proje", paths.resolve("src/.."))
        assertNull(paths.resolve(".."))
        assertNull(paths.resolve("/srv/projex"))
        assertNull(paths.resolve("/srv"))
        assertNull(paths.resolve(" "))
        assertNull(paths.resolve("a\u0000b"))
    }

    @Test
    fun `relative and parent stop at the root`() {
        assertEquals("", paths.relative("/srv/proje"))
        assertEquals("src/a.kt", paths.relative("/srv/proje/src/a.kt"))
        assertNull(paths.relative("/etc/passwd"))
        assertEquals("/srv/proje", paths.parent("/srv/proje/src"))
        assertEquals("/srv/proje/src", paths.parent("src/main"))
        assertNull(paths.parent("/srv/proje"))
    }

    @Test
    fun `the filesystem root can be the project`() {
        val top = TreePaths("/")
        assertEquals("/", top.root)
        assertEquals("/etc", top.resolve("etc"))
        assertEquals("etc/hosts", top.relative("/etc/hosts"))
        assertEquals("/", top.parent("/etc"))
        assertNull(top.parent("/"))
    }
}
