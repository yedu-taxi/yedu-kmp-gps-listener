package io.github.saggeldi.gps

import platform.Foundation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

class IosPositionSender : PositionSender {

    override fun sendPosition(request: String, onComplete: (Boolean) -> Unit) {
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
            val success = error == null && data != null
            dispatch_async(dispatch_get_main_queue()) {
                onComplete(success)
            }
        }.resume()
    }
}
