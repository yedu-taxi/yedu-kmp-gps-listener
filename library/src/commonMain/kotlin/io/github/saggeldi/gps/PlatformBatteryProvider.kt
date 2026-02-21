package io.github.saggeldi.gps

interface PlatformBatteryProvider {
    fun getBatteryStatus(): BatteryStatus
}
