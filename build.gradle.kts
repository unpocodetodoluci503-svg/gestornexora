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
        versionCode = 508
        versionName = "0.5.8"
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
    packaging {
        resources {
            excludes += listOf("META-INF/versions/**", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
        }
    }
}

dependencies {
    implementation("com.google.ai.edge.litert:litert:1.4.1")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.10")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("com.github.junrar:junrar:7.5.5")
}
