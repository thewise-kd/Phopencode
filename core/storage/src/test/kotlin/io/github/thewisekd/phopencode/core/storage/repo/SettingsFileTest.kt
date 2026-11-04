package io.github.thewisekd.phopencode.core.storage.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How the settings file reads back. Settings read from storage never crash: odd values are defaults. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class SettingsFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val stores by lazy { TestStores(folder.root) }

    @After
    fun close() {
        stores.close()
    }

    private fun repository(opened: TestStores.Opened<Preferences>): SettingsRepository =
        DataStoreSettingsRepository(
            opened.store,
            FakeSecretStore(),
            DataStoreProfileRepository(stores.profiles().store),
            DataStoreKnownHostsStore(stores.knownHosts().store),
        )

    @Test
    fun `settings survive reopening the file`() = runTest {
        val first = stores.settings()
        repository(first).apply {
            setLanguage(AppLanguage.TURKISH)
            setTheme(ThemePreference.DARK)
            setFileSizeLimit(42)
        }

        val reopened = repository(stores.reopen(first) { stores.settings(it) })

        assertEquals(AppSettings(AppLanguage.TURKISH, ThemePreference.DARK, 42), reopened.observe().first())
    }

    @Test
    fun `unknown names and values of the wrong type read as defaults`() = runTest {
        val opened = stores.settings()
        opened.store.edit {
            it[stringPreferencesKey("language")] = "KLINGON"
            it[stringPreferencesKey("theme")] = "sepia"
            it[stringPreferencesKey("file_size_limit_bytes")] = "huge"
        }

        assertEquals(AppSettings(), repository(opened).observe().first())
    }

    @Test
    fun `a file that is not a settings file reads as defaults and can be written again`() = runTest {
        val file = stores.newFile(TestStores.SETTINGS)
        file.writeText("not a preferences file")
        val repository = repository(stores.settings(file))

        assertEquals(AppSettings(), repository.observe().first())
        repository.setTheme(ThemePreference.LIGHT)
        assertEquals(ThemePreference.LIGHT, repository.observe().first().theme)
    }

    @Test
    fun `after a failed read an observer gets defaults, then later changes`() = runTest {
        val opened = stores.settings()
        val repository = repository(opened)
        opened.store.readFailures.set(1)

        repository.observe().test {
            assertEquals(AppSettings(), awaitItem())
            repository.setTheme(ThemePreference.DARK)
            assertEquals(ThemePreference.DARK, awaitItem().theme)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a file that never reads is retried slowly, not in a busy loop`() = runTest {
        val opened = stores.settings()
        opened.store.readFailures.set(Int.MAX_VALUE)
        val seen = mutableListOf<AppSettings>()
        backgroundScope.launch { repository(opened).observe().toList(seen) }

        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals(listOf(AppSettings()), seen)
        assertTrue(opened.store.reads.get() in 2..12, "reads in 10 seconds: ${opened.store.reads.get()}")
    }

    @Test
    fun `an error that is not IOException reaches the observer`() = runTest {
        val opened = stores.settings()
        val failing = object : DataStore<Preferences> by opened.store {
            override val data: Flow<Preferences> = flow { throw IllegalStateException("bug") }
        }
        val repository = DataStoreSettingsRepository(
            failing,
            FakeSecretStore(),
            DataStoreProfileRepository(stores.profiles().store),
            DataStoreKnownHostsStore(stores.knownHosts().store),
        )

        assertFailsWith<IllegalStateException> { repository.observe().first() }
    }

    @Test
    fun `a failing write throws IOException`() = runTest {
        val opened = stores.settings()
        opened.store.failing = true

        assertFailsWith<IOException> { repository(opened).setTheme(ThemePreference.DARK) }
    }
}
