package io.github.thewisekd.phopencode.core.storage.repo

import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the recent folders file holds on disk, and how it reads back what it did not write itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class RecentFoldersFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val stores by lazy { TestStores(folder.root) }

    @After
    fun close() {
        stores.close()
    }

    private val a = ServerProfileId("a")
    private val b = ServerProfileId("b")

    @Test
    fun `folders survive reopening the file, per profile and newest first`() = runTest {
        val first = stores.recentFolders()
        DataStoreRecentFoldersStore(first.store).apply {
            add(a, "/home/user/çalışma dizini")
            add(a, "/srv/project")
            add(b, "/srv/other")
        }

        val reopened = DataStoreRecentFoldersStore(stores.reopen(first) { stores.recentFolders(it) }.store)

        assertEquals(listOf("/srv/project", "/home/user/çalışma dizini"), reopened.list(a))
        assertEquals(listOf("/srv/other"), reopened.list(b))
    }

    @Test
    fun `a profile without folders leaves no entry in the file`() = runTest {
        val opened = stores.recentFolders()
        DataStoreRecentFoldersStore(opened.store).apply {
            add(a, "/srv/project")
            add(b, "/srv/other")
            forget(a)
            remove(b, "/srv/other")
        }

        assertEquals("""{"version":1,"profiles":[]}""", opened.file.readText())
    }

    @Test
    fun `a file this version did not write keeps the limits`() = runTest {
        val max = RecentFoldersStore.MAX_PER_PROFILE
        val many = (1..max + 3).joinToString(",") { "\"/srv/$it\"" }
        val file = stores.newFile(TestStores.RECENT_FOLDERS)
        file.writeText(
            """
            {"version":9,"future":1,"profiles":[
            {"profile":"a","folders":["/srv/1","/srv/1",2,null,$many],"future":true},
            {"profile":3,"folders":["/srv/x"]},
            {"profile":"c"},
            {"profile":"d","folders":[]},
            "not an object"
            ]}
            """.trimIndent(),
        )
        val store = DataStoreRecentFoldersStore(stores.recentFolders(file).store)

        assertEquals((1..max).map { "/srv/$it" }, store.list(a))
        assertEquals(emptyList(), store.list(ServerProfileId("c")))
        assertEquals(emptyList(), store.list(ServerProfileId("d")))
    }

    @Test
    fun `a file that is not a recent folders file reads as empty and can be written again`() = runTest {
        val file = stores.newFile(TestStores.RECENT_FOLDERS)
        file.writeText("not json at all {")
        val store = DataStoreRecentFoldersStore(stores.recentFolders(file).store)

        assertEquals(emptyList(), store.list(a))
        store.add(a, "/srv/project")
        assertEquals(listOf("/srv/project"), store.list(a))
    }
}
