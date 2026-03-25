package io.github.saggeldi.gps

actual object GpsFactory {

    actual fun createLocationProvider(): PlatformLocationProvider {
        return IosLocationProvider()
    }

    actual fun createBatteryProvider(): PlatformBatteryProvider {
        return IosBatteryProvider()
    }

    actual fun createPositionSender(): PositionSender {
        return IosPositionSender()
    }

    actual fun createNetworkMonitor(): NetworkMonitor {
        return IosNetworkMonitor()
    }

    actual fun createLocationPermissionHelper(): LocationPermissionHelper {
        return IosLocationPermissionHelper()
    }
}
