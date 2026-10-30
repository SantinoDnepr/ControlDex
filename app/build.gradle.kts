plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ua.controldex"
    compileSdk = 34
    defaultConfig {
        applicationId = "ua.controldex"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.4"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
