package io.github.saggeldi.gps

import android.content.Context
import android.os.BatteryManager

/**
 * Battery level via [BatteryManager] properties.
 *
 * This is read once per location fix, on the thread delivering the fix (the main looper
 * on Android), so it has to be cheap. The previous implementation pulled the sticky
 * `ACTION_BATTERY_CHANGED` broadcast with `registerReceiver(null, ...)` — a binder round
 * trip to system_server on every position.
 */
class AndroidBatteryProvider(
    context: Context
) : PlatformBatteryProvider {

    private val batteryManager =
        context.applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    override fun getBatteryStatus(): BatteryStatus {
        val manager = batteryManager ?: return BatteryStatus()
        // BATTERY_PROPERTY_CAPACITY is already a 0..100 percentage; anything else means
        // the platform has no reading (it returns Int.MIN_VALUE).
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return BatteryStatus(
            level = if (level in 0..100) level.toDouble() else 0.0,
            charging = manager.isCharging
        )
    }
}
