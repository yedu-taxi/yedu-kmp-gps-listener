package io.github.saggeldi.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle

class AndroidLocationProvider(
    private val context: Context
) : PlatformLocationProvider {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val batteryProvider = AndroidBatteryProvider(context)
    private var locationListener: LocationListener? = null

    companion object {
        private const val MINIMUM_INTERVAL_MS = 1000L
    }

    @SuppressLint("MissingPermission")
    override fun startUpdates(
        config: GpsConfig,
        onLocation: (Position) -> Unit,
        onError: (String) -> Unit
    ) {
        val provider = when (config.accuracy) {
            LocationAccuracy.HIGH -> LocationManager.GPS_PROVIDER
            LocationAccuracy.LOW -> LocationManager.PASSIVE_PROVIDER
            LocationAccuracy.MEDIUM -> LocationManager.NETWORK_PROVIDER
        }

        val minTimeMs = if (config.distance > 0 || config.angle > 0) {
            MINIMUM_INTERVAL_MS
        } else {
            config.interval * 1000
        }

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val battery = batteryProvider.getBatteryStatus()
                val position = location.toPosition(config.deviceId, battery)
                onLocation(position)
            }
            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                onError("Location provider disabled: $provider")
            }
        }

        try {
            locationManager.requestLocationUpdates(
                provider, minTimeMs, 0f, locationListener!!
            )
        } catch (e: SecurityException) {
            onError("Location permission not granted: ${e.message}")
        } catch (e: Exception) {
            onError("Location provider error: ${e.message}")
        }
    }

    override fun stopUpdates() {
        locationListener?.let {
            locationManager.removeUpdates(it)
        }
        locationListener = null
    }

    @SuppressLint("MissingPermission")
    override fun requestSingleLocation(
        config: GpsConfig,
        onLocation: (Position) -> Unit
    ) {
        try {
            val lastLocation =
                locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastLocation != null) {
                val battery = batteryProvider.getBatteryStatus()
                onLocation(lastLocation.toPosition(config.deviceId, battery))
            }
        } catch (_: SecurityException) {
        }
    }

    private fun Location.toPosition(deviceId: String, battery: BatteryStatus): Position {
        return Position(
            deviceId = deviceId,
            time = System.currentTimeMillis(),
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            speed = if (hasSpeed()) speed * 1.943844 else 0.0,
            course = if (hasBearing()) bearing.toDouble() else 0.0,
            accuracy = if (hasAccuracy()) accuracy.toDouble() else 0.0,
            battery = battery,
            mock = isMockLocation()
        )
    }

    private fun Location.isMockLocation(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            isMock
        } else {
            @Suppress("DEPRECATION")
            isFromMockProvider
        }
    }
}
