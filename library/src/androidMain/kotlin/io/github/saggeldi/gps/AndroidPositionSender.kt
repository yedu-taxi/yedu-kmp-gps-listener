package io.github.saggeldi.gps

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AndroidPositionSender : PositionSender {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * One reused worker rather than a thread per position: tracking posts every few
     * seconds for the whole shift, and serialising the sends also keeps positions from
     * reaching the server out of order.
     *
     * The queue is bounded. `Executors.newSingleThreadExecutor` uses an unbounded
     * one, so a link slower than the fix rate — or a single POST sitting out its
     * 15 s timeout — would pile up positions indefinitely and then flush them as
     * a burst of stale fixes, which the passenger sees as the car freezing and
     * then replaying its route. Discarding the oldest keeps the backlog recent.
     */
    private val executor = ThreadPoolExecutor(
        1, 1,
        0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_QUEUE_DEPTH),
        { runnable -> Thread(runnable, "yedu-gps-sender").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    companion object {
        private const val TIMEOUT = 15_000

        /**
         * How many position POSTs may wait behind the one in flight. At the 5 s
         * send interval this is ~2.5 minutes of backlog, which only builds up on
         * a link that is already failing.
         */
        private const val MAX_QUEUE_DEPTH = 32
    }

    override fun sendPosition(request: String, onComplete: (Boolean) -> Unit) {
        executor.execute {
            val success = sendSingle(request)
            handler.post { onComplete(success) }
        }
    }

    override fun sendPositions(requests: List<String>, onComplete: (List<Boolean>) -> Unit) {
        executor.execute {
            val results = requests.map { sendSingle(it) }
            handler.post { onComplete(results) }
        }
    }

    override fun sendJsonPost(url: String, jsonBody: String, token: String?, onComplete: (status: Int?, body: String?) -> Unit) {
        executor.execute {
            val (status, body) = sendJsonSingle(url, jsonBody, token)
            handler.post { onComplete(status, body) }
        }
    }

    private fun sendSingle(request: String): Boolean {
        return try {
            val url = URL(request)
            val connection = url.openConnection() as HttpURLConnection
            connection.readTimeout = TIMEOUT
            connection.connectTimeout = TIMEOUT
            connection.requestMethod = "POST"
            connection.connect()
            val inputStream = connection.inputStream
            while (inputStream.read() != -1) { /* drain */ }
            inputStream.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** @return the HTTP status (null when the request never reached the server) and the body. */
    private fun sendJsonSingle(url: String, jsonBody: String, token: String?): Pair<Int?, String?> {
        return try {
            val urlObj = URL(url)
            val connection = urlObj.openConnection() as HttpURLConnection
            connection.readTimeout = TIMEOUT
            connection.connectTimeout = TIMEOUT
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            connection.doOutput = true
            connection.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val responseCode = connection.responseCode
            val inputStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = inputStream?.use { stream -> stream.bufferedReader().readText() }
            Pair(responseCode, body)
        } catch (_: Exception) {
            Pair(null, null)
        }
    }
}
