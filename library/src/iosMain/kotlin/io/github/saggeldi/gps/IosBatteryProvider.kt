package io.github.saggeldi.gps

import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState

class IosBatteryProvider : PlatformBatteryProvider {

    override fun getBatteryStatus(): BatteryStatus {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        val level = device.batteryLevel
        return if (level >= 0) {
            BatteryStatus(
                level = (level * 100).toDouble(),
                charging = device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
                           device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateFull
            )
        } else {
            BatteryStatus(level = 0.0, charging = false)
        }
    }
}
