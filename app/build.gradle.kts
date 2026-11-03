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
        versionCode = 9
        versionName = "0.9"
    }
    // Стабільний підпис: keystore з GitHub Secrets (див. .github/workflows/build.yml).
    // Без змінних середовища — звичайний debug-ключ (локальна збірка).
    val ks = System.getenv("SIGNING_KEYSTORE")
    signingConfigs {
        if (ks != null) create("stable") {
            storeFile = file(ks)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") { if (ks != null) signingConfig = signingConfigs.getByName("stable") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
