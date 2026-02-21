@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.saggeldi.gps

import platform.CoreLocation.*
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject

/**
 * iOS implementation of [LocationPermissionHelper].
 *
 * Uses `CLLocationManager` for permission checks and requests,
 * and `CLLocationManager.locationServicesEnabled()` for GPS state.
 *
 * Note: iOS does not allow deep-linking to system Location Services
 * settings. [openLocationSettings] opens the app settings as a fallback.
 */
class IosLocationPermissionHelper : LocationPermissionHelper {

    private var locationManager: CLLocationManager? = null
    private var delegate: PermissionDelegate? = null

    override fun checkPermissionStatus(): PermissionStatus {
        return mapAuthorizationStatus(CLLocationManager.authorizationStatus())
    }

    override fun hasBackgroundPermission(): Boolean {
        return CLLocationManager.authorizationStatus() == kCLAuthorizationStatusAuthorizedAlways
    }

    override fun isLocationEnabled(): Boolean {
        return CLLocationManager.locationServicesEnabled()
    }

    override fun requestPermission(background: Boolean, callback: (PermissionStatus) -> Unit) {
        val manager = CLLocationManager()
        locationManager = manager

        delegate = PermissionDelegate(background, callback) {
            // Cleanup after callback fires
            locationManager = null
            delegate = null
        }
        manager.delegate = delegate

        if (background) {
            manager.requestAlwaysAuthorization()
        } else {
            manager.requestWhenInUseAuthorization()
        }
    }

    override fun openLocationSettings() {
        // iOS cannot deep-link to Location Services settings; open app settings instead
        openAppSettings()
    }

    override fun openAppSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString)
        if (url != null) {
            UIApplication.sharedApplication.openURL(url)
        }
    }

    private class PermissionDelegate(
        private val background: Boolean,
        private val callback: (PermissionStatus) -> Unit,
        private val cleanup: () -> Unit
    ) : NSObject(), CLLocationManagerDelegateProtocol {

        private var callbackFired = false

        override fun locationManager(
            manager: CLLocationManager,
            didChangeAuthorizationStatus: Int
        ) {
            // Skip the initial call that fires with the current status
            // before the user has interacted with the dialog
            val status = didChangeAuthorizationStatus
            if (status == kCLAuthorizationStatusNotDetermined) return

            if (!callbackFired) {
                callbackFired = true
                callback(mapAuthorizationStatus(status))
                manager.delegate = null
                cleanup()
            }
        }
    }
}

private fun mapAuthorizationStatus(status: Int): PermissionStatus {
    return when (status) {
        kCLAuthorizationStatusAuthorizedAlways,
        kCLAuthorizationStatusAuthorizedWhenInUse -> PermissionStatus.GRANTED
        kCLAuthorizationStatusDenied -> PermissionStatus.DENIED
        kCLAuthorizationStatusRestricted -> PermissionStatus.RESTRICTED
        kCLAuthorizationStatusNotDetermined -> PermissionStatus.NOT_DETERMINED
        else -> PermissionStatus.NOT_DETERMINED
    }
}
