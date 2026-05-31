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
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
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
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
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
    jvmToolchain(21)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ffmpeg-kit AAR — built from source by F-Droid via InfinityLoop1308/ffmpeg-kit srclib,
    // or placed manually in app/libs/ for local development.
    implementation(files("libs/ffmpeg-kit.aar"))
    // Required transitive dependency of ffmpeg-kit (NativeLoader references it at runtime)
    implementation("com.arthenica:smart-exception-java:0.2.1")
}

