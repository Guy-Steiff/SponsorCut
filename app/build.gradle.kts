import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Auto-download large binary dependencies if they are missing.
// This runs transparently before compilation so developers don't need to
// manually obtain these files.
// ---------------------------------------------------------------------------
data class LargeFile(val fileName: String, val url: String, val sha256: String? = null)

val largeFiles = listOf(
    LargeFile(
        fileName = "mobile-ffmpeg-full-gpl-4.4.LTS.aar",
        url = "https://github.com/tanersener/mobile-ffmpeg/releases/download/v4.4.LTS/mobile-ffmpeg-full-gpl-4.4.LTS.aar",
        // Optional integrity check – set to null to skip.
        sha256 = null
    )
)

val downloadLargeFiles by tasks.registering {
    group = "setup"
    description = "Downloads large binary files (AAR, etc.) if they are not present in libs/."
    notCompatibleWithConfigurationCache("Downloads files at execution time")

    doLast {
        val libsDir = file("libs")
        libsDir.mkdirs()

        largeFiles.forEach { lf ->
            val dest = libsDir.resolve(lf.fileName)
            if (dest.exists()) {
                logger.lifecycle("✔ ${lf.fileName} already present – skipping download.")
                return@forEach
            }

            logger.lifecycle("⬇ Downloading ${lf.fileName} …")
            logger.lifecycle("  Source: ${lf.url}")

            URI(lf.url).toURL().openStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }

            // Optional SHA-256 verification
            if (lf.sha256 != null) {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = dest.inputStream().use { stream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (stream.read(buffer).also { bytesRead = it } != -1) digest.update(buffer, 0, bytesRead)
                    digest.digest().joinToString("") { b -> "%02x".format(b) }
                }
                check(hash == lf.sha256) {
                    dest.delete()
                    "SHA-256 mismatch for ${lf.fileName}!\n  expected: ${lf.sha256}\n  got:      $hash\nFile removed – please try again."
                }
                logger.lifecycle("  SHA-256 verified ✔")
            }

            logger.lifecycle("  Saved to ${dest.absolutePath}")
        }
    }
}

// Hook into every compilation task so the download happens automatically.
afterEvaluate {
    tasks.matching { it.name.startsWith("compile") || it.name == "preBuild" }
        .configureEach { dependsOn(downloadLargeFiles) }
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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    // Bundled local FFmpeg AAR.
    implementation(files("libs/mobile-ffmpeg-full-gpl-4.4.LTS.aar"))
}

