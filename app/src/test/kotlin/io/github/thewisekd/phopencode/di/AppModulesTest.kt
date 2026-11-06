package io.github.thewisekd.phopencode.di

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.verify.verify
import kotlin.test.assertEquals

/** Plain JVM: the modules are only declarations, and verification does not run Android code. */
@OptIn(KoinExperimentalAPI::class)
class AppModulesTest {
    @Test
    fun `the app loads one module per feature`() {
        assertEquals(8, featureModules.size)
        assertEquals(featureModules.size, featureModules.toSet().size)
    }

    @Test
    fun `the app loads the core modules before the features`() {
        assertEquals(coreModules + featureModules, appModules)
        assertEquals(appModules.size, appModules.toSet().size)
    }

    @Test
    fun `every definition finds its dependencies`() {
        // One graph, as the app starts it: features take core objects from other modules, which a
        // per-module check would report as missing.
        module { includes(appModules) }.verify(
            // What Koin's Android setup and navigation provide at runtime, outside these modules;
            // and Diagnostics' flag, which its definition passes in itself, read from the app's flags.
            extraTypes = listOf(Context::class, Application::class, SavedStateHandle::class, Boolean::class),
        )
    }
}
