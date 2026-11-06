package io.github.thewisekd.phopencode.di

import io.github.thewisekd.phopencode.StopKoinRule
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The app's singletons run on the app scope even when a screen's presenter, built with that
 * screen's own scope as a Koin parameter, is what first creates them: leaving the screen ends its
 * scope, and must not close the connection manager or the transfer queue. The graph is the real
 * one that `PhopencodeApplication` starts; the levels of `CoreModulesTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AppScopeTest {
    @get:Rule
    val stopKoin = StopKoinRule()

    private val koin: Koin get() = GlobalContext.get()

    /**
     * A feature's contract type, by name: the app compiles against the features' UI modules only,
     * and their logic modules are on its runtime classpath.
     */
    private fun contract(feature: String, name: String): KClass<*> =
        Class.forName("io.github.thewisekd.phopencode.feature.$feature.logic.contract.$name").kotlin

    /** A screen's scope: its own [Job], as a destination's `viewModelScope` has. */
    private fun screenScope(): Pair<CoroutineScope, Job> {
        val job = Job()
        return CoroutineScope(job) to job
    }

    /** A profile whose password is not stored: a connect to it fails without the network. */
    private val profile = ServerProfile(
        id = ServerProfileId("app-scope-test"),
        name = "test",
        host = "127.0.0.1",
        port = 1,
        username = "test",
        auth = SshAuth.Password(SecretRef("app-scope-test-password")),
    )

    private fun assertManagerOutlives(build: (CoroutineScope) -> Any) = runBlocking {
        val (scope, job) = screenScope()
        // The screen's presenter is the first to ask for the manager, as on a fresh start.
        build(scope)
        job.cancelAndJoin()

        val manager = koin.get<ConnectionManager>()
        assertNotEquals<ConnectionState>(ConnectionState.Closed, manager.state.value, "leaving the screen closed the manager")
        manager.connect(profile)
        assertIs<ConnectionState.Ssh>(manager.state.value, "the manager ignored a connect")
        manager.close()
    }

    @Test
    fun `leaving the first screen that used the connection manager leaves it open`() {
        assertManagerOutlives { scope -> koin.get<Any>(contract("connect", "FolderPresenter")) { parametersOf(ConnectRoute.FolderPicker, scope) } }
    }

    @Test
    fun `leaving the progress screen that created the connection manager leaves it open`() {
        assertManagerOutlives { scope ->
            koin.get<Any>(contract("connect", "ProgressPresenter")) {
                parametersOf(ConnectRoute.Progress(profile.id.value), scope)
            }
        }
    }

    @Test
    fun `leaving the first transfer screen leaves the transfer queue on the app scope`() = runBlocking {
        val (scope, job) = screenScope()
        koin.get<Any>(contract("files", "TransferPresenter")) {
            parametersOf(FilesRoute.Transfer("/srv", "/srv/docs"), scope)
        }
        job.cancelAndJoin()

        val queue = koin.get<Any>(contract("files", "TransferQueue"))
        // The queue keeps its scope private; it is the one thing that tells where transfers run.
        val field = queue::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val queueScope = field.get(queue) as CoroutineScope
        assertTrue(queueScope.isActive, "leaving the screen ended the transfer queue's scope")
        assertSame(koin.get<AppScope>().scope, queueScope)
    }

    @Test
    fun `the app scope is a dedicated type, never a bare coroutine scope`() {
        assertNull(koin.getOrNull<CoroutineScope>())
    }
}
