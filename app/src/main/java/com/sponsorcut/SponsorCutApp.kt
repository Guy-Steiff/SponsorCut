package com.sponsorcut

import android.app.Application
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level

class SponsorCutApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Enable ffmpegkit log/stats redirection — required before any FFmpeg/FFprobe calls.
        // Without this, native stdout/stderr pipes are not set up and sessions return empty output.
        FFmpegKitConfig.enableRedirection()

        // Suppress ffmpegkit logs from printing to logcat (they would flood the log and
        // flash on screen via any debug overlay). All diagnostics go through DiagLog instead.
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_QUIET)
        FFmpegKitConfig.enableLogCallback { log ->
            // Route to DiagLog only — never to logcat or screen
            DiagLog.append("ffkit", log.message ?: "")
        }
    }
}

