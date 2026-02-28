package io.github.saggeldi.gps.sample

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform