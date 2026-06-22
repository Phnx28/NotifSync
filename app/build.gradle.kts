plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.phnx28.notifsync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phnx28.notifsync"
        minSdk = 29
        targetSdk = 34
        // v0.2.3 — architecture refactor + connection fix + log window
        versionCode = 6
        versionName = "0.2.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign the release APK with the debug key so it's installable
            // without a separate signing step. For personal-use sideloaded
            // apps this is fine; for Play Store distribution, replace with
            // a real release keystore via signingConfigs.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("androidx.navigation:navigation-fragment-ktx:2.8.0")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Bumped from 1.5.6 → 1.5.7 (GHSA-jvhw-rwqh-5xg5 — DoS on malformed frames)
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("com.google.code.gson:gson:2.11.0")

    // EncryptedSharedPreferences for the receiver's persisted PIN (AUDIT.md M-02)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.3")
}
