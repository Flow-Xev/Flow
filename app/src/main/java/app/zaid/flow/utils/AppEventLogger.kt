package app.zaid.flow.utils

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log

/**
 * Logs what the app itself is doing that nothing else writes down: screens opening and closing,
 * the app going to the background and coming back, and the system asking it to free memory.
 * Everything goes through [Log], so [FlowLogger] records it with the rest.
 */
object AppEventLogger {
    private const val TAG = "FlowEvents"

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = event(activity, if (savedInstanceState == null) "created" else "recreated")

                override fun onActivityStarted(activity: Activity) = event(activity, "started")

                override fun onActivityResumed(activity: Activity) = event(activity, "resumed")

                override fun onActivityPaused(activity: Activity) = event(activity, "paused")

                override fun onActivityStopped(activity: Activity) = event(activity, "stopped")

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) =
                    event(activity, if (activity.isChangingConfigurations) "recreating" else "destroyed")
            },
        )
        app.registerComponentCallbacks(
            object : ComponentCallbacks2 {
                override fun onLowMemory() {
                    if (FlowLogger.isEnabled) Log.w(TAG, "System is low on memory")
                }

                override fun onTrimMemory(level: Int) {
                    if (FlowLogger.isEnabled) Log.w(TAG, "System asked the app to trim memory (level $level)")
                }

                override fun onConfigurationChanged(newConfig: Configuration) {
                    if (FlowLogger.isEnabled) {
                        Log.i(
                            TAG,
                            "Configuration changed: orientation=${newConfig.orientation} locales=${newConfig.locales}",
                        )
                    }
                }
            },
        )
    }

    /** A navigation destination became the current screen. */
    fun screen(route: String?) {
        if (!FlowLogger.isEnabled) return
        Log.i(TAG, "Screen: ${route ?: "unknown"}")
    }

    private fun event(
        activity: Activity,
        what: String,
    ) {
        if (!FlowLogger.isEnabled) return
        Log.i(TAG, "${activity.javaClass.simpleName} $what")
    }
}
