@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.saggeldi.gps

import kotlinx.cinterop.useContents
import platform.CoreLocation.*
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

class IosLocationProvider : PlatformLocationProvider {

    private var locationManager: CLLocationManager? = null
    private var delegate: LocationDelegate? = null

    override fun startUpdates(
        config: GpsConfig,
        onLocation: (Position) -> Unit,
        onError: (String) -> Unit
    ) {
        val manager = CLLocationManager()
        locationManager = manager

        manager.desiredAccuracy = when (config.accuracy) {
            LocationAccuracy.HIGH -> kCLLocationAccuracyBest
            LocationAccuracy.MEDIUM -> kCLLocationAccuracyHundredMeters
            LocationAccuracy.LOW -> kCLLocationAccuracyKilometer
        }

        manager.pausesLocationUpdatesAutomatically = false
        manager.allowsBackgroundLocationUpdates = true

        delegate = LocationDelegate(config.deviceId, onLocation, onError)
        manager.delegate = delegate

        manager.requestAlwaysAuthorization()

        manager.startUpdatingLocation()
        manager.startMonitoringSignificantLocationChanges()
    }

    override fun stopUpdates() {
        locationManager?.stopUpdatingLocation()
        locationManager?.stopMonitoringSignificantLocationChanges()
        locationManager?.delegate = null
        locationManager = null
        delegate = null
    }

    override fun requestSingleLocation(
        config: GpsConfig,
        onLocation: (Position) -> Unit
    ) {
        locationManager?.location?.let { location ->
            val battery = IosBatteryProvider().getBatteryStatus()
            onLocation(location.toPosition(config.deviceId, battery))
        }
    }

    private class LocationDelegate(
        private val deviceId: String,
        private val onLocation: (Position) -> Unit,
        private val onError: (String) -> Unit
    ) : NSObject(), CLLocationManagerDelegateProtocol {

        private val batteryProvider = IosBatteryProvider()

        override fun locationManager(
            manager: CLLocationManager,
            didUpdateLocations: List<*>
        ) {
            for (loc in didUpdateLocations) {
                val location = loc as? CLLocation ?: continue
                val battery = batteryProvider.getBatteryStatus()
                val position = location.toPosition(deviceId, battery)
                onLocation(position)
            }
        }

        override fun locationManager(
            manager: CLLocationManager,
            didFailWithError: platform.Foundation.NSError
        ) {
            onError("Location error: ${didFailWithError.localizedDescription}")
        }

        override fun locationManager(
            manager: CLLocationManager,
            didChangeAuthorizationStatus: Int
        ) {
            if (didChangeAuthorizationStatus == kCLAuthorizationStatusAuthorizedAlways ||
                didChangeAuthorizationStatus == kCLAuthorizationStatusAuthorizedWhenInUse
            ) {
                manager.startUpdatingLocation()
            }
        }
    }
}

internal fun CLLocation.toPosition(deviceId: String, battery: BatteryStatus): Position {
    return Position(
        deviceId = deviceId,
        time = (timestamp.timeIntervalSince1970 * 1000).toLong(),
        latitude = coordinate.useContents { latitude },
        longitude = coordinate.useContents { longitude },
        altitude = altitude,
        speed = if (speed >= 0) speed * 1.943844 else 0.0,
        course = if (course >= 0) course else 0.0,
        accuracy = horizontalAccuracy,
        battery = battery,
        mock = false
    )
}
