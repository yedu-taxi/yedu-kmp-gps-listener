import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    id("com.google.devtools.ksp")
    alias(libs.plugins.androidx.room)
}

group = "io.github.saggeldi"
version = "0.1.10"

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // AGP 9: the Android-KMP library block is `android {}` (`androidLibrary {}` is gone).
    android {
        namespace = "io.github.saggeldi.gps"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTest {}

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        androidMain.dependencies {
            implementation("androidx.core:core-ktx:1.12.0")
            implementation(libs.androidx.room.sqlite.wrapper)
            // Fused location: sensor-fused, assisted fixes. Raw GPS_PROVIDER
            // drops out badly in urban canyons and tunnels.
            implementation(libs.play.services.location)
        }

        commonMain.dependencies {
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // Needed to drive the suspending PositionDao path in PaidDistanceTest.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
    add("kspIosX64", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    // Add any other platform target you use in your project, for example kspDesktop
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "yedu-kmp-gps-listener", version.toString())

    pom {
        name = "Yedu KMP GPS Listener"
        description = "Kotlin Multiplatform GPS background listener library"
        inceptionYear = "2026"
        url = "https://github.com/yedu-taxi/yedu-kmp-gps-listener"

        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }

        developers {
            developer {
                id = "saggeldi"
                name = "Shageldi Alyyew"
                url = "https://shageldi.dev"
            }
        }

        scm {
            url = "https://github.com/yedu-taxi/yedu-kmp-gps-listener"
            connection = "scm:git:git://github.com/yedu-taxi/yedu-kmp-gps-listener.git"
            developerConnection = "scm:git:ssh://git@github.com/yedu-taxi/yedu-kmp-gps-listener.git"
        }
    }
}
