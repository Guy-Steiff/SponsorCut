package com.sponsorcut

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File

class ProcessingService : Service() {

    companion object {
        const val CHANNEL_ID = "sponsorcut_processing"
        const val NOTIF_ID = 1
        const val NOTIF_DONE_ID = 2
        const val ACTION_PROCESS = "com.sponsorcut.PROCESS"
        const val EXTRA_URI = "uri"
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_ID_SOURCE = "id_source"
        const val EXTRA_OUTPUT_FOLDER_URI = "output_folder_uri"
        const val EXTRA_FRAME_ACCURATE = "frame_accurate"

        const val BROADCAST_PROGRESS = "com.sponsorcut.PROGRESS"
        const val EXTRA_PROGRESS_TEXT = "progress_text"
        const val EXTRA_DONE = "done"
        const val EXTRA_ERROR = "error"
    }

    private val tag = "ProcessingService"
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        try {
            startForeground(NOTIF_ID, buildNotification("SponsorCut", "Starting…"))
        } catch (e: Exception) {
            Log.e(tag, "startForeground failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_PROCESS) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString.isNullOrBlank()) { stopSelf(startId); return START_NOT_STICKY }

        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        val idSource = intent.getStringExtra(EXTRA_ID_SOURCE) ?: "manual"
        val outputFolderUri = intent.getStringExtra(EXTRA_OUTPUT_FOLDER_URI)
        val frameAccurate = intent.getBooleanExtra(EXTRA_FRAME_ACCURATE, false)
        val uri = try { Uri.parse(uriString) } catch (e: Exception) {
            broadcast(text = "Bad URI: $uriString\n${e.message}", error = true)
            finish(startId)
            return START_NOT_STICKY
        }

        Thread {
            var tempOutput: File? = null
            try {
                progress("Copying input video…")
                val inputFile = FileResolver.uriToFile(this, uri)
                val sourceName = FileResolver.getDisplayName(this, uri)
                val outputFileName = FileResolver.outputFileNameFromSource(sourceName)
                val outputTarget = if (!outputFolderUri.isNullOrBlank()) {
                    FileResolver.createOutputTargetInTree(this, Uri.parse(outputFolderUri), outputFileName)
                } else {
                    FileResolver.createOutputTarget(this, uri, outputFileName, inputFile)
                }

                tempOutput = File(cacheDir, "processed_${System.currentTimeMillis()}.mp4")

                progress("Fetching SponsorBlock segments…")
                val segments = SponsorBlockClient().fetchRich(videoId)
                Log.i(tag, "SponsorBlock result: $segments")

                if (segments.isEmpty()) {
                    broadcast(
                        text = "SponsorBlock had no segments for ID: $videoId\n\nCorrect the ID and try again.",
                        done = false
                    )
                    finish(startId)
                    return@Thread
                }

                val sortedSegs = segments.sortedBy { it.start }
                val totalCut = sortedSegs.sumOf { it.end - it.start }
                progress("Removing ${segments.size} segment(s) (~%.1fs total)… [${if (frameAccurate) "frame-accurate" else "fast"}]".format(totalCut))

                FfmpegEngine.process(
                    inputFile, tempOutput,
                    sortedSegs.map { it.start to it.end },
                    cacheDir,
                    frameAccurate = frameAccurate
                ) { step -> progress(step) }

                progress("Saving output…")
                FileResolver.writeToOutputTarget(this, tempOutput, outputTarget)

                val codecs = FfmpegEngine.probeCodecs(inputFile)
                val codecInfo = buildString {
                    append("Video: ${codecs.videoCodec}")
                    if (codecs.width != null && codecs.height != null) append(" ${codecs.width}×${codecs.height}")
                    if (!codecs.fps.isNullOrBlank()) append(" @${codecs.fps}fps")
                    if (!codecs.videoBitrate.isNullOrBlank()) append(" ${codecs.videoBitrate}kbps")
                    appendLine()
                    append("Audio: ${codecs.audioCodec ?: "none"}")
                    if (!codecs.sampleRate.isNullOrBlank()) append(" ${codecs.sampleRate}Hz")
                    if (!codecs.audioBitrate.isNullOrBlank()) append(" ${codecs.audioBitrate}kbps")
                }

                val segSummary = sortedSegs.joinToString("\n") {
                    "  [${it.category}] %.1fs → %.1fs (%.1fs removed)".format(it.start, it.end, it.end - it.start)
                }

                val result = "✓ Done — ID: $videoId\n\n" +
                    "Removed ${segments.size} segment(s) (~%.1fs):\n".format(totalCut) +
                    "$segSummary\n\n" +
                    "$codecInfo\n\n" +
                    "Saved to:\n${outputTarget.label}"

                broadcast(text = result, done = true)
                showDoneNotification("SponsorCut done ✓", "Saved: ${outputTarget.label}")

            } catch (e: Exception) {
                Log.e(tag, "Processing failed", e)
                broadcast(text = "Failed: ${e.message ?: "unknown error"}", error = true)
                showDoneNotification("SponsorCut failed", e.message ?: "unknown error")
            } finally {
                tempOutput?.delete()
                finish(startId)
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun finish(startId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        stopSelf(startId)
    }

    private fun progress(text: String) {
        nm.notify(NOTIF_ID, buildNotification("SponsorCut", text))
        broadcast(text = text)
    }

    private fun broadcast(text: String, done: Boolean = false, error: Boolean = false) {
        sendBroadcast(Intent(BROADCAST_PROGRESS).apply {
            `package` = packageName        // explicit package prevents SecurityException on Android 14
            putExtra(EXTRA_PROGRESS_TEXT, text)
            putExtra(EXTRA_DONE, done)
            putExtra(EXTRA_ERROR, error)
        })
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "SponsorCut", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Video processing progress"
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, body: String?): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }

    private fun showDoneNotification(title: String, body: String) {
        val pi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        }
        nm.notify(NOTIF_DONE_ID, n)
    }
}

