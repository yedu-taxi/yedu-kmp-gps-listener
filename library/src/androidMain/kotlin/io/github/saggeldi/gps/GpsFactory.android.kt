package io.github.saggeldi.gps

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.Dispatchers

@SuppressLint("StaticFieldLeak")
actual object GpsFactory {

    private var appContext: Context? = null

    /**
     * Initialize with Android Context. Call once in Application.onCreate()
     * or Service.onCreate() before using any factory methods.
     *
     * ```kotlin
     * class MyApp : Application() {
     *     override fun onCreate() {
     *         super.onCreate()
     *         GpsFactory.initialize(this)
     *     }
     * }
     * ```
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context {
        return checkNotNull(appContext) {
            "GpsFactory.initialize(context) must be called before using factory methods on Android"
        }
    }

    actual fun createLocationProvider(): PlatformLocationProvider {
        return AndroidLocationProvider(requireContext())
    }

    actual fun createBatteryProvider(): PlatformBatteryProvider {
        return AndroidBatteryProvider(requireContext())
    }

    actual fun createPositionSender(): PositionSender {
        return AndroidPositionSender()
    }

    actual fun createNetworkMonitor(): NetworkMonitor {
        return AndroidNetworkMonitor(requireContext())
    }

    actual fun createLocationPermissionHelper(): LocationPermissionHelper {
        return AndroidLocationPermissionHelper(requireContext())
    }

    actual fun createPositionDatabase(): PositionDatabase {
        val context = requireContext()
        return androidx.room.Room.databaseBuilder<PositionDatabase>(
            context = context,
            name = context.getDatabasePath("positions.db").absolutePath
        ).setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }
}
