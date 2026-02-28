package io.github.saggeldi.gps

interface PlatformLocationProvider {
    fun startUpdates(
        config: GpsConfig,
        onLocation: (Position) -> Unit,
        onError: (String) -> Unit
    )

    fun stopUpdates()

    fun requestSingleLocation(
        config: GpsConfig,
        onLocation: (Position) -> Unit
    )
}
