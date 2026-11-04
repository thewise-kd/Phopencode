package io.github.thewisekd.phopencode.feature.connect.logic.folder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FolderPathsTest {
    @Test
    fun `an absolute canonical path stays as it is`() {
        assertEquals("/srv/app", FolderPaths.canonical("/srv/app"))
        assertEquals("/", FolderPaths.canonical("/"))
    }

    @Test
    fun `trailing and repeated slashes are dropped, except for the root`() {
        assertEquals("/srv/app", FolderPaths.canonical("/srv/app/"))
        assertEquals("/srv/app", FolderPaths.canonical("//srv///app//"))
        assertEquals("/", FolderPaths.canonical("///"))
    }

    @Test
    fun `dot segments are removed and dot-dot goes up, never above the root`() {
        assertEquals("/srv/app", FolderPaths.canonical("/srv/./app/."))
        assertEquals("/srv", FolderPaths.canonical("/srv/app/.."))
        assertEquals("/other", FolderPaths.canonical("/srv/app/../../other"))
        assertEquals("/", FolderPaths.canonical("/../../.."))
        assertEquals("/srv", FolderPaths.canonical("/../srv"))
    }

    @Test
    fun `a relative path resolves against the base`() {
        assertEquals("/srv/app", FolderPaths.canonical("app", base = "/srv"))
        assertEquals("/app", FolderPaths.canonical("app", base = "/"))
        assertEquals("/srv", FolderPaths.canonical("app/..", base = "/srv"))
        assertEquals("/srv", FolderPaths.canonical("", base = "/srv"))
    }

    @Test
    fun `names that only look special stay plain names`() {
        assertEquals("/srv/...", FolderPaths.canonical("/srv/..."))
        assertEquals("/srv/.hidden", FolderPaths.canonical("/srv/.hidden"))
        assertEquals("/srv/a b", FolderPaths.canonical("/srv/a b"))
    }

    @Test
    fun `a path with a NUL byte is refused`() {
        assertNull(FolderPaths.canonical("/srv/a\u0000b"))
    }

    @Test
    fun `the parent of a folder is the path without its last segment`() {
        assertEquals("/srv", FolderPaths.parent("/srv/app"))
        assertEquals("/", FolderPaths.parent("/srv"))
        assertEquals("/", FolderPaths.parent("/"))
    }

    @Test
    fun `a child is joined without a double slash`() {
        assertEquals("/srv", FolderPaths.child("/", "srv"))
        assertEquals("/srv/app", FolderPaths.child("/srv", "app"))
    }
}
