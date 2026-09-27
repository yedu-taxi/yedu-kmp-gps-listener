package io.github.saggeldi.gps

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

class IosPositionSender : PositionSender {

    private companion object {
        /**
         * How many position POSTs may wait behind the one in flight before the
         * oldest are discarded. At the 5 s send interval this is ~2.5 minutes of
         * backlog, which only builds up on a link that is already failing.
         */
        const val MAX_QUEUE_DEPTH = 32
    }

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

    /**
     * A position POST waiting for its turn on the wire.
     */
    private class PendingPost(
        val url: String,
        val jsonBody: String,
        val token: String?,
        val onComplete: (status: Int?, body: String?) -> Unit
    )

    // Guarded by the main queue: every mutation and every read below happens
    // inside a dispatch_async(main) block or on the completion handler that hops
    // back to it, so no additional locking is needed.
    private val queue = mutableListOf<PendingPost>()
    private var inFlight = false

    /**
     * Position POSTs are sent strictly one at a time, in order.
     *
     * These used to go straight onto `NSURLSession.sharedSession`, which runs
     * them concurrently. Two requests in flight can reach the server in either
     * order, and the backend applies whatever arrives last — so the passenger's
     * car marker would jump backwards. Android has always serialised its sends
     * for this reason; iOS did not, which is why the two platforms behaved
     * differently on the same trip.
     */
    override fun sendJsonPost(url: String, jsonBody: String, token: String?, onComplete: (status: Int?, body: String?) -> Unit) {
        dispatch_async(dispatch_get_main_queue()) {
            if (queue.size >= MAX_QUEUE_DEPTH) {
                // The link is slower than the fix rate. Drop from the front: the
                // oldest positions are the least useful, and dropping the newest
                // instead would leave the passenger looking at a stale car.
                val dropped = queue.removeAt(0)
                dropped.onComplete(null, null)
            }
            queue.add(PendingPost(url, jsonBody, token, onComplete))
            pumpQueue()
        }
    }

    /** Must be called on the main queue. */
    private fun pumpQueue() {
        if (inFlight) return
        val next = queue.removeFirstOrNull() ?: return
        inFlight = true
        execute(next)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun execute(post: PendingPost) {
        val nsUrl = NSURL.URLWithString(post.url)
        if (nsUrl == null) {
            finish(post, null, null)
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
        post.token?.let {
            urlRequest.setValue("Bearer $it", forHTTPHeaderField = "Authorization")
        }
        urlRequest.setHTTPBody(
            NSString.create(string = post.jsonBody).dataUsingEncoding(NSUTF8StringEncoding)
        )

        NSURLSession.sharedSession.dataTaskWithRequest(urlRequest) { data, response, error ->
            // A transport error carries no status; the controller reads that
            // absence as "retry later" rather than as an auth problem.
            val status = if (error == null) (response as? NSHTTPURLResponse)?.statusCode?.toInt() else null
            val body = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding) as? String }
            dispatch_async(dispatch_get_main_queue()) {
                finish(post, status, body)
            }
        }.resume()
    }

    /** Must be called on the main queue. */
    private fun finish(post: PendingPost, status: Int?, body: String?) {
        inFlight = false
        post.onComplete(status, body)
        pumpQueue()
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
