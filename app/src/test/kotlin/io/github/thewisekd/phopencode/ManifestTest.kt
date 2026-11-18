package io.github.thewisekd.phopencode

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.keepalive.KeepAliveService
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    @Test
    fun `the main activity is single top, so a deep link reaches the running one`() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
    }

    /** The merged manifest's `<service>` of the keep-alive (P9.39). */
    private val keepAliveService: Element by lazy {
        val services = application.getElementsByTagName("service")
        val name = KeepAliveService::class.java.name
        val found = (0 until services.length).map { services.item(it) as Element }
            .filter { it.getAttributeNS(ANDROID_NAMESPACE, "name") == name }
        found.single()
    }

    @Test
    fun `the keep-alive service is private, of the special-use type, and says what it is for`() {
        assertEquals("false", keepAliveService.getAttributeNS(ANDROID_NAMESPACE, "exported"))
        assertEquals("specialUse", keepAliveService.getAttributeNS(ANDROID_NAMESPACE, "foregroundServiceType"))
        // Left at its default: the service does not outlive a task the user removed on purpose.
        assertFalse(keepAliveService.hasAttributeNS(ANDROID_NAMESPACE, "stopWithTask"))
        val properties = keepAliveService.getElementsByTagName("property")
        val subtype = (0 until properties.length).map { properties.item(it) as Element }
            .single { it.getAttributeNS(ANDROID_NAMESPACE, "name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" }
        assertTrue("SSH" in subtype.getAttributeNS(ANDROID_NAMESPACE, "value"))

        val info = context.packageManager.getServiceInfo(ComponentName(context, KeepAliveService::class.java), 0)
        assertFalse(info.exported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
        }
    }

    @Test
    fun `the app asks for the foreground service and notification permissions`() {
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        listOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
        ).forEach { assertTrue(it in requested, it) }
        // The keep-alive holds no wake lock.
        assertFalse("android.permission.WAKE_LOCK" in requested)
    }

    private companion object {
        const val TEST_CONFIG = "com/android/tools/test_config.properties"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
