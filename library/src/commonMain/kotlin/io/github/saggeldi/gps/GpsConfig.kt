package io.github.saggeldi.gps

data class GpsConfig(
    val deviceId: String,
    val interval: Long = 300,
    val distance: Double = 0.0,
    val angle: Double = 0.0,
    val accuracy: LocationAccuracy = LocationAccuracy.MEDIUM,
    /**
     * Fastest rate fixes are delivered at when a distance/angle trigger is set (the tracker needs
     * fixes faster than [interval] to test them). 1 s matches the old fixed behaviour; an online
     * driver waiting for an offer does fine with 2–3 s, which halves GNSS wake-ups.
     */
    val fastestIntervalMillis: Long = 1_000L,
    /**
     * Lets the OS batch fixes for up to this long before delivering them together
     * (`LocationRequest.setMaxUpdateDelayMillis`); 0 = deliver each fix at once.
     */
    val maxBatchDelayMillis: Long = 0L,
)
