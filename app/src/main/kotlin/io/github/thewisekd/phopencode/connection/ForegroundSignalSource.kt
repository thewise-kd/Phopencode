package io.github.thewisekd.phopencode.connection

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionSignals

/**
 * Tells [signals] when the app comes to the foreground: when the first of its activities starts
 * while none was started. It counts started activities through the application's activity
 * lifecycle callbacks, which run on the main thread. An activity that stops only to be made again
 * for a configuration change, such as a rotation, is not a trip to the background.
 *
 * The application calls [start] once, for the life of the process; a second call does nothing.
 */
class ForegroundSignalSource(private val signals: ConnectionSignals) : Application.ActivityLifecycleCallbacks {
    /** Main thread only. */
    private var registered = false
    private var started = 0

    /**
     * Activities stopped for a configuration change whose replacements have not started yet. They
     * still count in [started]: each replacement's start continues one of them, and is no new
     * foreground. A count, not a flag, so that activities recreated together are each matched.
     */
    private var recreating = 0

    fun start(application: Application) {
        if (registered) return
        registered = true
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (recreating > 0) {
            recreating--
            return
        }
        started++
        if (started == 1) signals.appForegrounded()
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity.isChangingConfigurations) {
            recreating++
            return
        }
        if (started > 0) started--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
