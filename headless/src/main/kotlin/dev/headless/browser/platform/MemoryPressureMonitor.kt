package dev.headless.browser.platform

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * System memory pressure observer for Android.
 *
 * Listens to system [ComponentCallbacks2.onTrimMemory] events and maintains a state
 * indicating whether the device is under critical memory pressure.
 */
public object MemoryPressureMonitor : ComponentCallbacks2 {

    private val isCritical = AtomicBoolean(false)
    private val isRegistered = AtomicBoolean(false)

    /**
     * Registers this monitor with the application [Context].
     */
    public fun register(context: Context) {
        if (isRegistered.compareAndSet(false, true)) {
            val appCtx = context.applicationContext ?: context
            appCtx.registerComponentCallbacks(this)
        }
    }

    /**
     * Unregisters this monitor from the application [Context].
     */
    public fun unregister(context: Context) {
        if (isRegistered.compareAndSet(true, false)) {
            val appCtx = context.applicationContext ?: context
            appCtx.unregisterComponentCallbacks(this)
        }
    }

    /**
     * Checks if the device is currently under critical memory pressure.
     */
    public fun isCriticalMemory(): Boolean = isCritical.get()

    override fun onTrimMemory(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                isCritical.set(true)
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // Low/moderate pressure; reset critical flag if memory recovered
                isCritical.set(false)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun onLowMemory() {
        isCritical.set(true)
    }
}
