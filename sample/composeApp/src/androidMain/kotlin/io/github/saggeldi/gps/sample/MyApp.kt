package io.github.saggeldi.gps.sample

import android.app.Application
import io.github.saggeldi.gps.GpsFactory

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GpsFactory.initialize(this)
    }
}