plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sponsorcut"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sponsorcut"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "/Users/gsm/sponsorcut-release.jks")
            storePassword = System.getenv("KEYSTORE_PASS") ?: "sponsorcut123"
            keyAlias = System.getenv("KEY_ALIAS") ?: "sponsorcut"
            keyPassword = System.getenv("KEY_PASS") ?: "sponsorcut123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ffmpeg-kit replaces mobile-ffmpeg; AAR included in libs/ for reproducible builds.
    implementation(files("libs/mobile-ffmpeg-full-gpl-4.4.LTS.aar"))
}
