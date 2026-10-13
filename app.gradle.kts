plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.videodownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.videodownloader"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin { jvmToolchain(17) }

dependencies {
    // 0.18.+ picks the newest 0.18 release (newest yt-dlp) each time GitHub builds the app.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.+")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.+")
}
