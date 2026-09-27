package io.github.saggeldi.gps

/** Metres per second → knots, the unit the Traccar/OsmAnd URL protocol expects. */
internal const val MPS_TO_KNOTS = 1.943844

/** Metres per second → km/h, the unit the Yedu JSON API expects. */
internal const val MPS_TO_KMH = 3.6

data class Position(
    val id: Long = 0,
    val deviceId: String,
    val time: Long,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    /**
     * Ground speed in **metres per second** — what both `Location.getSpeed()`
     * and `CLLocation.speed` report natively, kept unconverted so nothing is
     * lost at capture.
     *
     * The two wire formats want different units and each converts on its way
     * out: [ProtocolFormatter.formatRequest] emits knots, [ProtocolFormatter
     * .formatJsonBody] emits km/h. Never send this value raw.
     */
    val speed: Double = 0.0,
    val course: Double = 0.0,
    val accuracy: Double = 0.0,
    val battery: BatteryStatus = BatteryStatus(),
    val mock: Boolean = false,
    val tripId: String? = null,
    val tripStatus: String? = null,
    val km: Double = 0.0
)
