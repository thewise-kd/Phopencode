package io.github.thewisekd.phopencode.feature.notify.ui.pending

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.core.navigation.DeepLinks
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The app's only activity; the feature's manifest adds the filter to it by name when the app merges. */
private const val MAIN_ACTIVITY = "io.github.thewisekd.phopencode.MainActivity"

private const val ANDROID = "http://schemas.android.com/apk/res/android"

private const val TEST_CONFIG = "com/android/tools/test_config.properties"

/**
 * The notify ui manifest gives the app's activity the intent filter of [DeepLinks.PENDING], so a
 * notification's tap, a VIEW of `phopencode://pending` from a browser or the ntfy app, starts the app;
 * the graph's deep link then opens the pending list. Reads the manifest the build packs for these tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class PendingDeepLinkManifestTest {
    private val activities: List<Element> by lazy {
        val config = Properties()
        val stream = assertNotNull(javaClass.classLoader?.getResourceAsStream(TEST_CONFIG), "no $TEST_CONFIG")
        stream.use(config::load)
        val manifest = File(assertNotNull(config.getProperty("android_merged_manifest")))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val nodes = factory.newDocumentBuilder().parse(manifest).getElementsByTagName("activity")
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.names(tag: String): Set<String> = children(tag).map { it.getAttributeNS(ANDROID, "name") }.toSet()

    @Test
    fun `the main activity has the pending deep link's filter`() {
        val uri = Uri.parse(DeepLinks.PENDING)
        val activity = activities.single { it.getAttributeNS(ANDROID, "name") == MAIN_ACTIVITY }
        assertEquals("true", activity.getAttributeNS(ANDROID, "exported"))

        val filter = activity.children("intent-filter").single { filter ->
            filter.children("data").any {
                it.getAttributeNS(ANDROID, "scheme") == uri.scheme && it.getAttributeNS(ANDROID, "host") == uri.host
            }
        }

        assertEquals(setOf(Intent.ACTION_VIEW), filter.names("action"))
        assertEquals(setOf(Intent.CATEGORY_DEFAULT, Intent.CATEGORY_BROWSABLE), filter.names("category"))
        // Nothing but the scheme and the host: no path, port or other data that would widen it.
        val data = filter.children("data").single()
        assertEquals(
            setOf("scheme", "host"),
            (0 until data.attributes.length).map { data.attributes.item(it).localName }.toSet(),
        )
    }

    @Test
    fun `a view of the pending link resolves to the main activity`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DeepLinks.PENDING)).addCategory(Intent.CATEGORY_BROWSABLE)

        val names = context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }

        assertTrue(MAIN_ACTIVITY in names, "resolved to $names")
    }

    @Test
    fun `another host of the scheme resolves to nothing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("phopencode://elsewhere")).addCategory(Intent.CATEGORY_BROWSABLE)

        assertEquals(emptyList(), context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name })
    }
}
