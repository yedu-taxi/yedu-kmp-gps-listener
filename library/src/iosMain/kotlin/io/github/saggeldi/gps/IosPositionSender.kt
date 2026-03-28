package io.github.saggeldi.gps

import kotlinx.cinterop.ExperimentalForeignApi
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

    @OptIn(ExperimentalForeignApi::class)
    override fun sendJsonPost(url: String, jsonBody: String, token: String?, onComplete: (Boolean, String?) -> Unit) {
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            onComplete(false, null)
            return
        }
        val urlRequest = NSMutableURLRequest(
            uRL = nsUrl,
            cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
            timeoutInterval = 15.0
        )
        urlRequest.setHTTPMethod("POST")
        urlRequest.setValue("application/json", forHTTPHeaderField = "Content-Type")
        urlRequest.setValue("application/json", forHTTPHeaderField = "Accept")
        if (token != null) {
            urlRequest.setValue("Bearer $token", forHTTPHeaderField = "Authorization")
        }
        urlRequest.setHTTPBody(NSString.create(string = jsonBody).dataUsingEncoding(NSUTF8StringEncoding))

        NSURLSession.sharedSession.dataTaskWithRequest(urlRequest) { data, response, error ->
            val httpResponse = response as? NSHTTPURLResponse
            val success = error == null && httpResponse != null && httpResponse.statusCode in 200..299
            val body = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding) as? String }
            dispatch_async(dispatch_get_main_queue()) {
                onComplete(success, body)
            }
        }.resume()
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
