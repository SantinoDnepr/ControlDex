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
        // Релізна збірка: тим самим стабільним ключем (оновлення поверх попередніх версій),
        // локально без ключа — debug-ключем, щоб APK можна було встановити.
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (ks != null) signingConfigs.getByName("stable") else signingConfigs.getByName("debug")
        }
    }
    // Ім'я вихідного файлу: ControlDex.apk (замість app-debug.apk / app-release.apk)
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "ControlDex.apk"
        }
    }
    // lintVital у release не запускаємо (як і для debug) — щоб CI не падав на попередженнях
    lint { checkReleaseBuilds = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
