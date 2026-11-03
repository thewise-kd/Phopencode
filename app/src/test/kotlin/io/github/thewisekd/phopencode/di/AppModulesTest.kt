package io.github.thewisekd.phopencode.di

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verifyAll
import kotlin.test.assertEquals

/** Plain JVM: the modules are only declarations, and verification does not run Android code. */
@OptIn(KoinExperimentalAPI::class)
class AppModulesTest {
    @Test
    fun `the app loads one module per feature`() {
        assertEquals(8, appModules.size)
        assertEquals(appModules.size, appModules.toSet().size)
    }

    @Test
    fun `every definition finds its dependencies`() {
        // What Koin's Android setup and navigation provide at runtime, outside these modules.
        appModules.verifyAll(
            extraTypes = listOf(Context::class, Application::class, SavedStateHandle::class),
        )
    }
}
