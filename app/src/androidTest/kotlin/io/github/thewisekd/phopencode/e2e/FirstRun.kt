package io.github.thewisekd.phopencode.e2e

import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.rules.ExternalResource
import org.koin.mp.KoinPlatform

/**
 * The first-run screens, out of the way of the end-to-end tests (P14.3). The tests start from a
 * fresh install, where the usage guide (P14.1) would open above the server list and the live
 * preview notice (P13.24) would cover the port picker. This is the one place that ends both, through
 * the app's own [SettingsRepository] from its Koin graph, as the guide's Skip and the notice's
 * "I have read and understood" do.
 *
 * A test that launches `MainActivity` applies [FirstRunDoneRule] as the rule outside the one that
 * launches the activity (`@get:Rule(order = 0)`, the activity's rule after it), so that the flags
 * are stored before the activity reads them. A test that clears the app's data, through
 * [SettingsRepository.deleteAllData] or [startFirstRunAgain], calls [endFirstRun] again before
 * the activity is launched again, and in its tear-down, so that no later test meets the guide or
 * the notice.
 *
 * With `noticePending = true`, for the test of the notice itself, the rule instead deletes all
 * data and marks only the guide seen, still before the activity starts, so that its first read of
 * the settings already finds the guide seen and the notice not accepted. Because it deletes all
 * data, that mode runs only with the end-to-end arguments (see [E2eEnvironment.fromArguments]),
 * and it ends the first run again once the test is over, after the activity has closed.
 *
 * Only the debug-only tests in `e2e.preview` use this: the releaseCheck build's keep rules
 * (`app/proguard-release-check.pro`) do not cover the flags' members of [SettingsRepository].
 *
 * Public, unlike the helpers below: JUnit 4 reads a rule through a public getter, and a public
 * property cannot expose an internal type.
 */
class FirstRunDoneRule(private val noticePending: Boolean = false) : ExternalResource() {
    override fun before() = runBlocking {
        if (!noticePending) {
            endFirstRun()
            return@runBlocking
        }
        E2eEnvironment.fromArguments()
        val settings = appSettings()
        startFirstRunAgain(settings)
        settings.markGuideSeen()
        awaitSettings(settings, "the guide seen and the notice not accepted") { it.guideSeen && !it.previewNoticeAccepted }
        Unit
    }

    override fun after() {
        if (noticePending) runBlocking { endFirstRun() }
    }
}

/** The app's settings, from the Koin graph `PhopencodeApplication` started. */
internal fun appSettings(): SettingsRepository = KoinPlatform.getKoin().get()

/**
 * Marks the usage guide seen and the live preview notice accepted, and returns once the stored
 * settings say so.
 */
internal suspend fun endFirstRun(settings: SettingsRepository = appSettings()) {
    settings.markGuideSeen()
    settings.acceptPreviewNotice()
    awaitSettings(settings, "the guide seen and the preview notice accepted") {
        it.guideSeen && it.previewNoticeAccepted
    }
}

/**
 * "Delete all data", as on a fresh install: every store is cleared and the settings return to
 * their defaults, so the guide and the notice show again. Returns once the stored settings say so.
 * The caller restores the flags with [endFirstRun] when it is done.
 */
internal suspend fun startFirstRunAgain(settings: SettingsRepository = appSettings()) {
    settings.deleteAllData()
    awaitSettings(settings, "the first-run flags cleared") { !it.guideSeen && !it.previewNoticeAccepted }
}

/** Waits for stored settings that meet [done]. */
internal suspend fun awaitSettings(settings: SettingsRepository, what: String, done: (AppSettings) -> Boolean): AppSettings =
    try {
        withTimeout(E2eApp.STEP) { settings.observe().first(done) }
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError("Waited ${E2eApp.STEP} for $what in the settings", timeout)
    }
