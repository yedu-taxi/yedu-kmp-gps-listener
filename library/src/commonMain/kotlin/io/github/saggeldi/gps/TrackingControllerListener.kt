package io.github.saggeldi.gps

interface TrackingControllerListener {
    fun onPositionUpdate(position: Position) {}
    fun onPositionSent(position: Position) {}
    fun onSendFailed(position: Position) {}
    fun onError(error: String) {}

    /**
     * Location POSTs are being rejected as unauthorized and refreshing the token
     * did not help. Nothing the controller does can recover from here: the
     * driver is online but absent from the geo-index, so the app layer should
     * take them out of the online state and say why rather than leave them
     * waiting for rides that can never be offered.
     */
    fun onAuthFailure() {}
    fun onStatusChange(status: TrackerStatus) {}
    fun onFareReceived(fare: String) {}
}
