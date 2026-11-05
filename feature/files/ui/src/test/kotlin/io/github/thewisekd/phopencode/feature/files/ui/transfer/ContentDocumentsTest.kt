package io.github.thewisekd.phopencode.feature.files.ui.transfer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AUTHORITY = "phopencode.test.documents"

/** A provider of one file per URI path segment, with the names and sizes the test sets. */
class TestDocumentsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        queries++
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        val entry = names[uri.lastPathSegment] ?: return cursor
        cursor.addRow(columns.map<String, Any?> { if (it == OpenableColumns.SIZE) entry.second else entry.first }.toTypedArray())
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        when (mode) {
            in rejectedModes -> throw IllegalArgumentException("Unsupported mode")
            in missingModes -> throw FileNotFoundException("Unsupported mode")
        }
        // "w" does not truncate here, as with some real providers.
        val parsed = if (mode == "w") ParcelFileDescriptor.MODE_WRITE_ONLY else ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file(uri), parsed)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (file(uri).delete()) 1 else 0

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun file(uri: Uri) = File(directory, uri.lastPathSegment!!)

    companion object {
        lateinit var directory: File
        var queries = 0

        /** Modes refused with IllegalArgumentException and with FileNotFoundException. */
        val rejectedModes = mutableSetOf<String>()
        val missingModes = mutableSetOf<String>()

        /** Display name and size by URI segment; null values are left out of the row. */
        val names = mutableMapOf<String, Pair<String?, Long?>>()
    }
}

/**
 * Picked documents reach the queue as [LocalDocument][io.github.thewisekd.phopencode.feature.files.logic.contract.LocalDocument]s
 * read through the content resolver; a real provider's documents only the emulator opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ContentDocumentsTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val documents = ContentDocuments(context.contentResolver)

    @Before
    fun provider() {
        TestDocumentsProvider.directory = kotlin.io.path.createTempDirectory("documents").toFile()
        TestDocumentsProvider.names.clear()
        TestDocumentsProvider.queries = 0
        TestDocumentsProvider.rejectedModes.clear()
        TestDocumentsProvider.missingModes.clear()
        Robolectric.setupContentProvider(TestDocumentsProvider::class.java, AUTHORITY)
    }

    @After
    fun clean() {
        TestDocumentsProvider.directory.deleteRecursively()
    }

    private fun uri(segment: String): Uri = Uri.parse("content://$AUTHORITY/doc/$segment")

    @Test
    fun `the name and size come from the provider`() {
        TestDocumentsProvider.names["1"] = "rapor.pdf" to 12L

        val document = documents.open(uri("1"), fallbackName = "upload", forWriting = false)

        assertEquals("rapor.pdf", document.name)
        assertEquals(12L, document.sizeBytes)
    }

    @Test
    fun `a missing name falls back, and a missing size is unknown`() {
        TestDocumentsProvider.names["2"] = null to null

        val document = documents.open(uri("2"), fallbackName = "upload", forWriting = false)

        assertEquals("upload", document.name)
        assertNull(document.sizeBytes)
    }

    @Test
    fun `a name is never a path`() {
        assertEquals("c.txt", documentName("a/b/c.txt", "upload"))
        assertEquals("upload", documentName("a/", "upload"))
        assertEquals("upload", documentName("..", "upload"))
        assertEquals("upload", documentName("  ", "upload"))
        assertEquals("upload", documentName(null, "upload"))
    }

    @Test
    fun `it reads, replaces and deletes the content`() {
        TestDocumentsProvider.names["3"] = "notlar.md" to null
        val file = File(TestDocumentsProvider.directory, "3")
        file.writeText("eski uzun içerik")
        val document = documents.open(uri("3"), fallbackName = "upload", forWriting = true)

        assertEquals("eski uzun içerik", document.openInput().use { it.readBytes().decodeToString() })
        document.openOutput().use { it.write("yeni".toByteArray()) }
        assertEquals("yeni", file.readText())
        assertTrue(document.delete())
        assertFalse(file.exists())
    }

    @Test
    fun `opening reads nothing, the name and size are read once when first asked`() {
        TestDocumentsProvider.names["4"] = "rapor.pdf" to 12L

        val document = documents.open(uri("4"), fallbackName = "upload", forWriting = false)
        assertEquals(0, TestDocumentsProvider.queries)

        assertEquals("rapor.pdf", document.name)
        assertEquals(12L, document.sizeBytes)
        assertEquals(1, TestDocumentsProvider.queries)
    }

    @Test
    fun `opening keeps the picker's grant past the activity, and idle releases it`() {
        val resolver = context.contentResolver
        documents.open(uri("5"), fallbackName = "upload", forWriting = false)
        documents.open(uri("6"), fallbackName = "upload", forWriting = true)

        val held = resolver.persistedUriPermissions.associate { it.uri to (it.isReadPermission to it.isWritePermission) }
        assertEquals(mapOf(uri("5") to (true to false), uri("6") to (true to true)), held)

        releaseTransferGrants(resolver)

        assertTrue(resolver.persistedUriPermissions.isEmpty())
    }

    @Test
    fun `without the truncating mode, rwt replaces the content whole`() {
        TestDocumentsProvider.rejectedModes += "wt"
        val file = File(TestDocumentsProvider.directory, "7")
        file.writeText("eski uzun içerik")
        val document = documents.open(uri("7"), fallbackName = "download", forWriting = true)

        document.openOutput().use { it.write("yeni".toByteArray()) }

        assertEquals("yeni", file.readText())
    }

    @Test
    fun `a provider that cannot truncate fails the write rather than leave old bytes behind`() {
        TestDocumentsProvider.rejectedModes += "wt"
        TestDocumentsProvider.missingModes += "rwt"
        val file = File(TestDocumentsProvider.directory, "8")
        file.writeText("eski uzun içerik")
        val document = documents.open(uri("8"), fallbackName = "download", forWriting = true)

        assertFailsWith<FileNotFoundException> { document.openOutput() }

        assertEquals("eski uzun içerik", file.readText())
    }
}
