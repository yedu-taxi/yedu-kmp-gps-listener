package io.github.saggeldi.gps

/**
 * Cross-platform helper for checking and requesting location permissions
 * and querying whether device location services are enabled.
 *
 * Obtain via [GpsFactory.createLocationPermissionHelper].
 *
 * **Android note:** Permission requesting requires an Activity.
 * Call `setActivity(activity)` on the Android implementation before
 * calling [requestPermission]. Without an Activity the callback
 * receives the current status without showing a system dialog.
 */
interface LocationPermissionHelper {

    /** Synchronously check the current location permission status. */
    fun checkPermissionStatus(): PermissionStatus

    /** Whether background (always) location permission has been granted. */
    fun hasBackgroundPermission(): Boolean

    /** Whether device location services (GPS) are currently enabled. */
    fun isLocationEnabled(): Boolean

    /**
     * Request location permission from the user.
     *
     * @param background If `true`, requests background/always permission.
     * @param callback Invoked with the resulting [PermissionStatus].
     */
    fun requestPermission(background: Boolean = false, callback: (PermissionStatus) -> Unit)

    /** Open device location/GPS settings (e.g. to enable GPS). */
    fun openLocationSettings()

    /** Open app-specific permission settings. */
    fun openAppSettings()
}
