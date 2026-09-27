package io.github.saggeldi.gps

interface PositionSender {
    fun sendPosition(request: String, onComplete: (Boolean) -> Unit)
    fun sendPositions(requests: List<String>, onComplete: (List<Boolean>) -> Unit)

    /**
     * Send a position as a JSON POST request with Bearer token auth.
     *
     * The HTTP status reaches the caller unreduced. Collapsing it into a boolean
     * made an expired token indistinguishable from a dropped connection, so the
     * controller kept retrying a dead token for the whole shift instead of
     * refreshing it — see [TrackingController.tokenRefresher].
     *
     * @param url Full URL endpoint (e.g. https://api.example.com/api/v1/trips/driver-location)
     * @param jsonBody JSON string body
     * @param token Bearer token for Authorization header (nullable)
     * @param onComplete `status` is the HTTP status code, or `null` when the
     *   request never got an answer (no route, timeout, malformed URL);
     *   `body` is the response body when one was read.
     */
    fun sendJsonPost(url: String, jsonBody: String, token: String?, onComplete: (status: Int?, body: String?) -> Unit)
}
