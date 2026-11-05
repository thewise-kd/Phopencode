package io.github.thewisekd.phopencode.feature.files.ui.tree

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The path bar's folders, from the project's folder down to the one shown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class PathSegmentsTest {
    @Test
    fun `the project's folder alone is one segment named after it`() {
        assertEquals(listOf(PathSegment(ROOT_NAME, ROOT)), pathSegments(ROOT, ROOT))
    }

    @Test
    fun `each folder below the project's folder is a segment`() {
        assertEquals(
            listOf(PathSegment(ROOT_NAME, ROOT), PathSegment("src", SRC), PathSegment("main", MAIN)),
            pathSegments(ROOT, MAIN),
        )
    }

    @Test
    fun `a trailing slash changes nothing`() {
        assertEquals(
            listOf(PathSegment(ROOT_NAME, ROOT), PathSegment("src", SRC)),
            pathSegments("$ROOT/", "$SRC/"),
        )
    }

    @Test
    fun `the filesystem root as project folder keeps its slash`() {
        assertEquals(
            listOf(PathSegment("/", "/"), PathSegment("srv", "/srv"), PathSegment("app", "/srv/app")),
            pathSegments("/", "/srv/app"),
        )
    }

    @Test
    fun `a folder that only shares a prefix is not below`() {
        assertEquals(listOf(PathSegment(ROOT_NAME, ROOT), PathSegment("$ROOT-old", "$ROOT-old")), pathSegments(ROOT, "$ROOT-old"))
    }

    @Test
    fun `printing a segment hides its path`() {
        assertEquals("PathSegment(***)", PathSegment("src", SRC).toString())
    }
}
