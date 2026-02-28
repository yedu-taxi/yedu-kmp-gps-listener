package io.github.saggeldi.gps

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL

class AndroidPositionSender : PositionSender {

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TIMEOUT = 15_000
    }

    override fun sendPosition(request: String, onComplete: (Boolean) -> Unit) {
        Thread {
            val success = sendSingle(request)
            handler.post { onComplete(success) }
        }.start()
    }

    override fun sendPositions(requests: List<String>, onComplete: (List<Boolean>) -> Unit) {
        Thread {
            val results = requests.map { sendSingle(it) }
            handler.post { onComplete(results) }
        }.start()
    }

    override fun sendJsonPost(url: String, jsonBody: String, token: String?, onComplete: (Boolean) -> Unit) {
        Thread {
            val success = sendJsonSingle(url, jsonBody, token)
            handler.post { onComplete(success) }
        }.start()
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

    private fun sendJsonSingle(url: String, jsonBody: String, token: String?): Boolean {
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
            inputStream?.use { stream ->
                while (stream.read() != -1) { /* drain */ }
            }
            responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }
}
