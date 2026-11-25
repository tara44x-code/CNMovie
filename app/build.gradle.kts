plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.muhannadsatlink.cnmovie"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.muhannadsatlink.cnmovie"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "1.6"
    }

    // مفتاح توقيع ثابت (اختياري): إذا وُجد app/release.jks وكلمة السر بالبيئة
    val ks = file("release.jks")
    val ksPass = System.getenv("KEYSTORE_PASSWORD")
    signingConfigs {
        if (ks.exists() && !ksPass.isNullOrEmpty()) {
            create("release") {
                storeFile = ks
                storePassword = ksPass
                keyAlias = System.getenv("KEY_ALIAS") ?: "cnmovie"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
}
