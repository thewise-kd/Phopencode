package io.github.thewisekd.phopencode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The `app` manifest is final after P1.10: later tasks fill in the XML files it points to, and
 * declare permissions and services in their feature's manifest. This reads the merged manifest the
 * build packs for these tests. The levels of `MainActivityTest`; the Pi passes 26 only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ManifestTest {
    @get:Rule
    val koin = StopKoinRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val application: Element by lazy {
        val config = Properties()
        val stream = assertNotNull(javaClass.classLoader?.getResourceAsStream(TEST_CONFIG), "no $TEST_CONFIG")
        stream.use(config::load)
        val manifest = File(assertNotNull(config.getProperty("android_merged_manifest")))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(manifest)
        document.getElementsByTagName("application").item(0) as Element
    }

    private fun attribute(name: String): String = application.getAttributeNS(ANDROID_NAMESPACE, name)

    /** The name of the first element of an XML resource. */
    private fun rootOf(id: Int): String = context.resources.getXml(id).use { parser ->
        while (parser.next() != XmlPullParser.START_TAG) {
            check(parser.eventType != XmlPullParser.END_DOCUMENT) { "empty XML resource" }
        }
        parser.name
    }

    @Test
    fun `the application class starts the app`() {
        assertEquals(PhopencodeApplication::class.java.name, attribute("name"))
    }

    @Test
    fun `the locale config lists the app languages`() {
        assertEquals("@xml/locales_config", attribute("localeConfig"))
        assertEquals("locale-config", rootOf(R.xml.locales_config))
    }

    @Test
    fun `the network security config points to its file`() {
        assertEquals("@xml/network_security_config", attribute("networkSecurityConfig"))
        assertEquals("network-security-config", rootOf(R.xml.network_security_config))
    }

    @Test
    fun `the data extraction rules point to their file`() {
        assertEquals("@xml/data_extraction_rules", attribute("dataExtractionRules"))
        assertEquals("data-extraction-rules", rootOf(R.xml.data_extraction_rules))
    }

    @Test
    fun `backup stays off`() {
        assertEquals("false", attribute("allowBackup"))
    }

    @Test
    fun `the app uses its own theme`() {
        assertEquals("@style/Theme.Phopencode", attribute("theme"))
    }

    private companion object {
        const val TEST_CONFIG = "com/android/tools/test_config.properties"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
