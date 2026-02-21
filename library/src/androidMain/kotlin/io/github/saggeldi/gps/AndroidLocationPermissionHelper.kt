package io.github.saggeldi.gps

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

/**
 * Android implementation of [LocationPermissionHelper].
 *
 * Uses `ContextCompat.checkSelfPermission` for permission checks and
 * `ActivityCompat.requestPermissions` for permission requests.
 *
 * **Important:** Call [setActivity] with a live Activity before calling
 * [requestPermission] so the system dialog can be shown. Without an
 * Activity the callback receives the current status without prompting.
 *
 * After requesting, relay the result from your Activity:
 * ```kotlin
 * override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
 *     super.onRequestPermissionsResult(requestCode, permissions, grantResults)
 *     permissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
 * }
 * ```
 */
class AndroidLocationPermissionHelper(private val context: Context) : LocationPermissionHelper {

    private var activityRef: WeakReference<Activity>? = null
    private var pendingCallback: ((PermissionStatus) -> Unit)? = null
    private var pendingBackground: Boolean = false

    /**
     * Set the Activity used for permission request dialogs.
     * Stored as a [WeakReference] to avoid leaking the Activity.
     */
    fun setActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    /** Clear the Activity reference. */
    fun clearActivity() {
        activityRef = null
    }

    override fun checkPermissionStatus(): PermissionStatus {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            return PermissionStatus.GRANTED
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wasRequested = prefs.getBoolean(KEY_PERMISSION_REQUESTED, false)

        return if (wasRequested) PermissionStatus.DENIED else PermissionStatus.NOT_DETERMINED
    }

    override fun hasBackgroundPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Before Android 10, background location is included in fine/coarse grant
            return checkPermissionStatus() == PermissionStatus.GRANTED
        }
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    override fun requestPermission(background: Boolean, callback: (PermissionStatus) -> Unit) {
        val activity = activityRef?.get()
        if (activity == null) {
            // No Activity available - return current status without prompting
            callback(checkPermissionStatus())
            return
        }

        // Mark that we have requested at least once
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PERMISSION_REQUESTED, true)
            .apply()

        pendingCallback = callback
        pendingBackground = background

        if (background && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Background permission must be requested separately after foreground is granted
            if (checkPermissionStatus() != PermissionStatus.GRANTED) {
                // Request foreground first
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    REQUEST_CODE_FOREGROUND
                )
            } else {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    REQUEST_CODE_BACKGROUND
                )
            }
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_CODE_FOREGROUND
            )
        }
    }

    /**
     * Relay permission results from the Activity.
     *
     * Call this from `Activity.onRequestPermissionsResult()`.
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_CODE_FOREGROUND -> {
                val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
                if (granted && pendingBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Foreground granted, now request background
                    val activity = activityRef?.get()
                    if (activity != null) {
                        ActivityCompat.requestPermissions(
                            activity,
                            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                            REQUEST_CODE_BACKGROUND
                        )
                        return // Wait for background result
                    }
                }
                pendingCallback?.invoke(checkPermissionStatus())
                pendingCallback = null
            }
            REQUEST_CODE_BACKGROUND -> {
                pendingCallback?.invoke(
                    if (hasBackgroundPermission()) PermissionStatus.GRANTED else PermissionStatus.DENIED
                )
                pendingCallback = null
            }
        }
    }

    override fun openLocationSettings() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        const val REQUEST_CODE_FOREGROUND = 9001
        const val REQUEST_CODE_BACKGROUND = 9002
        private const val PREFS_NAME = "gps_permission_prefs"
        private const val KEY_PERMISSION_REQUESTED = "location_permission_requested"
    }
}
