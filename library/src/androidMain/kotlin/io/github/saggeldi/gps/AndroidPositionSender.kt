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
            val success = try {
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
            handler.post { onComplete(success) }
        }.start()
    }
}
