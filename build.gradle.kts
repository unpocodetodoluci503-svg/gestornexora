plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.20"
}

android {
    namespace = "com.gestor.archivos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gestor.archivos"
        minSdk = 26
        targetSdk = 34
        versionCode = 501
        versionName = "0.5.1"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("nexora.keystore")
            storePassword = "nexora123"
            keyAlias = "nexora"
            keyPassword = "nexora123"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.ai.edge.litert:litert:1.4.1")
}
