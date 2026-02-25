package io.github.saggeldi.gps

import platform.Foundation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

class IosPositionSender : PositionSender {

    override fun sendPosition(request: String, onComplete: (Boolean) -> Unit) {
        sendSingle(request) { success ->
            dispatch_async(dispatch_get_main_queue()) {
                onComplete(success)
            }
        }
    }

    override fun sendPositions(requests: List<String>, onComplete: (List<Boolean>) -> Unit) {
        if (requests.isEmpty()) {
            onComplete(emptyList())
            return
        }
        val results = MutableList(requests.size) { false }
        var remaining = requests.size

        requests.forEachIndexed { index, request ->
            sendSingle(request) { success ->
                results[index] = success
                remaining--
                if (remaining == 0) {
                    dispatch_async(dispatch_get_main_queue()) {
                        onComplete(results.toList())
                    }
                }
            }
        }
    }

    private fun sendSingle(request: String, onComplete: (Boolean) -> Unit) {
        val url = NSURL.URLWithString(request)
        if (url == null) {
            onComplete(false)
            return
        }
        val urlRequest = NSMutableURLRequest(
            uRL = url,
            cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
            timeoutInterval = 15.0
        )
        urlRequest.setHTTPMethod("POST")

        NSURLSession.sharedSession.dataTaskWithRequest(urlRequest) { data, _, error ->
            onComplete(error == null && data != null)
        }.resume()
    }
}
