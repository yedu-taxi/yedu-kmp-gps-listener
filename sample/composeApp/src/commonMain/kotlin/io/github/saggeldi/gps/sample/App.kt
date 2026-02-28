package io.github.saggeldi.gps.sample

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.saggeldi.gps.GpsConfig
import io.github.saggeldi.gps.GpsFactory
import io.github.saggeldi.gps.GpsTrackerListener
import io.github.saggeldi.gps.LocationAccuracy
import io.github.saggeldi.gps.Position
import io.github.saggeldi.gps.TrackerStatus
import io.github.saggeldi.gps.TrackingControllerListener
import io.github.saggeldi.gps.createTrackingController
import org.jetbrains.compose.resources.painterResource

import sample.composeapp.generated.resources.Res
import sample.composeapp.generated.resources.compose_multiplatform

@Composable
@Preview
fun App() {



    val myListener = object : GpsTrackerListener {
        override fun onPositionUpdate(position: Position) {
            println("${position.latitude}, ${position.longitude}")
            println("Battery: ${position.battery.level}%")
        }
        override fun onError(error: String) {
            println("GPS: "+ error)
        }
        override fun onStatusChange(status: TrackerStatus) {
            println("GPS "+ "Tracker: $status")
        }
    }

    val controllerListener = object : TrackingControllerListener {
        override fun onPositionUpdate(position: Position) {
            println("GPS: "+ "New: ${position.latitude}, ${position.longitude}")
        }
        override fun onPositionSent(position: Position) {
            println("GPS: "+ "Sent to server")
        }
        override fun onSendFailed(position: Position) {
            println("GPS: "+ "Send failed, will retry")
        }
        override fun onError(error: String) {
            println("GPS: "+ error)
        }
        override fun onStatusChange(status: TrackerStatus) {
            println("GPS: "+ "Tracker: $status")
        }
    }

    fun startGps() {

        // Full pipeline mode (GPS + caching + sending + retry)
        val controller = GpsFactory.createTrackingController(
            serverUrl = "https://your-server.com:5055",
            buffer = true,
            listener = controllerListener
        )
        controller.start(GpsConfig(
            deviceId = "my-device-123",
            interval = 300,
            accuracy = LocationAccuracy.HIGH
        ))
    }
    MaterialTheme {
        var showContent by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = { startGps() }) {
                Text("Start GPS")
            }
        }
    }
}