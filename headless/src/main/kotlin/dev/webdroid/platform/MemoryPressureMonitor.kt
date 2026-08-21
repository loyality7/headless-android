package dev.webdroid.platform

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

    /** Called by the OS as memory pressure rises or falls; updates the critical flag accordingly. */
    override fun onTrimMemory(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                isCritical.set(true)
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                // These are foreground memory-pressure levels below critical: a
                // real signal that pressure eased, not just that the app changed
                // visibility. UI_HIDDEN, BACKGROUND and (background) MODERATE are
                // deliberately excluded — they fire on a foreground/background
                // transition regardless of memory state, and clearing the flag on
                // one of those used to let a session marked critical un-mark
                // itself the instant the host app was merely backgrounded, with
                // nothing actually reclaimed.
                isCritical.set(false)
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                // Visibility/background-depth signals, not a memory-recovered
                // signal: deliberately left as a no-op. See the comment above.
            }
        }
    }

    /** Called by the OS on device configuration changes; not a memory signal, so a no-op here. */
    override fun onConfigurationChanged(newConfig: Configuration) {}

    /** Called by the OS under severe memory pressure; always marks critical. */
    override fun onLowMemory() {
        isCritical.set(true)
    }
}
