plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.hymt2test"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.hymt2test"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    // Prebuilt llama.cpp wrapper. Does not require NDK/CMake on the app side.
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
