package io.github.saggeldi.gps

interface PositionSender {
    fun sendPosition(request: String, onComplete: (Boolean) -> Unit)
    fun sendPositions(requests: List<String>, onComplete: (List<Boolean>) -> Unit)

    /**
     * Send a position as a JSON POST request with Bearer token auth.
     * @param url Full URL endpoint (e.g. https://api.example.com/api/v1/trips/driver-location)
     * @param jsonBody JSON string body
     * @param token Bearer token for Authorization header (nullable)
     * @param onComplete callback with success/failure
     */
    fun sendJsonPost(url: String, jsonBody: String, token: String?, onComplete: (Boolean) -> Unit)
}
