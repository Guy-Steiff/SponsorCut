// app/src/main/java/com/sponsorcut/SponsorCutApp.kt

package com.sponsorcut

import android.app.Application
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level

class SponsorCutApp : Application() {

    override fun onCreate() {
        super.onCreate()

        android.util.Log.i("SponsorCutLaunch", "Application.onCreate: FFmpegKit startup starting")

        FFmpegKitConfig.enableRedirection()
        android.util.Log.i("SponsorCutLaunch", "Application.onCreate: redirection enabled")

        FFmpegKitConfig.setLogLevel(Level.AV_LOG_INFO)
        android.util.Log.i("SponsorCutLaunch", "Application.onCreate: log level set")

        FFmpegKitConfig.enableLogCallback { log ->
            try {
                DiagLog.append("ffkit", log.message ?: "")
            } catch (_: Throwable) {
            }
        }

        android.util.Log.i("SponsorCutLaunch", "Application.onCreate: FFmpegKit startup complete")
        DiagLog.append("ffkit", "SponsorCut diagnostic build 2026-10-19-17:37; FFmpeg log level=INFO")
    }
}
