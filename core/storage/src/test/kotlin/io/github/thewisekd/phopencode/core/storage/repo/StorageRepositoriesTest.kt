package io.github.thewisekd.phopencode.core.storage.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app's entry point: one set of repositories over the app's private files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class StorageRepositoriesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the repositories keep their files in the app's private DataStore folder and share deleteAllData`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secrets = FakeSecretStore()
        val repositories = StorageRepositories(context, secrets, scope)
        try {
            SecretValue.ofText("value").use { secrets.put(SecretRef("ref"), it) }
            repositories.profiles.save(
                ServerProfile(ServerProfileId("a"), "Home", "server.invalid", username = "user", auth = SshAuth.Password(SecretRef("ref"))),
            )
            repositories.knownHosts.approve(HostEndpoint("server.invalid", 22), HostKeyFingerprint("SHA256:abc"))
            repositories.settings.setTheme(ThemePreference.DARK)

            val folder = File(context.filesDir, "datastore")
            assertEquals(
                setOf("profiles.json", "known_hosts.json", "settings.preferences_pb"),
                folder.list().orEmpty().toSet(),
            )
            assertTrue("ref" in File(folder, "profiles.json").readText())

            repositories.settings.deleteAllData()

            assertEquals(SecretReadResult.NotFound, secrets.read(SecretRef("ref")))
            assertEquals(emptyList(), repositories.profiles.list())
            assertEquals(null, repositories.knownHosts.approved(HostEndpoint("server.invalid", 22)))
            assertEquals(ThemePreference.SYSTEM, repositories.settings.observe().first().theme)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
