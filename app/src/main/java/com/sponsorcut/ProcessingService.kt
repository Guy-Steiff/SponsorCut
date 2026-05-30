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
import android.os.PowerManager
import android.util.Log
import java.io.File

class ProcessingService : Service() {

    companion object {
        const val CHANNEL_ID = "sponsorcut_processing"
        const val NOTIF_ID = 1
        const val NOTIF_DONE_ID = 2
        const val ACTION_PROCESS = "com.sponsorcut.PROCESS"
        const val ACTION_CANCEL = "com.sponsorcut.CANCEL"
        const val EXTRA_URI = "uri"
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_ID_SOURCE = "id_source"
        const val EXTRA_OUTPUT_FOLDER_URI = "output_folder_uri"
        const val EXTRA_FRAME_ACCURATE = "frame_accurate"

        const val BROADCAST_PROGRESS = "com.sponsorcut.PROGRESS"
        const val EXTRA_PROGRESS_TEXT = "progress_text"
        const val EXTRA_PROGRESS_CURRENT = "progress_current"
        const val EXTRA_PROGRESS_TOTAL = "progress_total"
        const val EXTRA_DONE = "done"
        const val EXTRA_ERROR = "error"
        const val EXTRA_CANCELLED = "cancelled"
    }

    private val tag = "ProcessingService"
    private lateinit var nm: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

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
        if (intent?.action == ACTION_CANCEL) {
            Log.i(tag, "Cancel requested")
            com.arthenica.ffmpegkit.FFmpegKit.cancel()
            return START_NOT_STICKY
        }
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
            var outputTarget: OutputTarget? = null
            // Acquire CPU wake lock — keeps processing running when screen turns off
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sponsorcut:processing")
                .also { it.acquire(2 * 60 * 60 * 1000L) }  // max 2 hours
            try {
                DiagLog.clear()
                DiagLog.append("Service", "Starting. frameAccurate=$frameAccurate uri=$uriString")
                progress("Copying input video…")
                val inputFile = FileResolver.uriToFile(this, uri)
                DiagLog.append("Service", "Input cached: ${inputFile.absolutePath} size=${inputFile.length()}")
                val sourceName = FileResolver.getDisplayName(this, uri)
                val sourceExt = sourceName.substringAfterLast('.', "mp4").lowercase().ifBlank { "mp4" }
                val plan = run {
                    // Need to inspect first to know if we're re-encoding (affects output extension)
                    progress("Inspecting video…")
                    val info = FFProbeInspector.inspect(inputFile)
                        ?: error("ffprobe could not read the video file — unsupported format?")
                    DiagLog.append("FFProbe", "isTsEncapsulated=${info.isTsEncapsulated} codec=${info.videoCodec} audio=${info.audioCodec} summary=${info.summaryLine}")
                    TranscodePolicy.plan(info, frameAccurate).also { p ->
                        DiagLog.append("Plan", "canCopyVideo=${p.canCopyVideo} canCopyAudio=${p.canCopyAudio} useSlowSeek=${p.useSlowSeek} rationale=${p.rationale}")
                        progress("Plan: ${p.rationale}")
                    } to info
                }
                val (processingPlan, videoInfo) = plan

                // Always preserve the source extension — ffmpeg handles any codec in any container.
                val outputExt = sourceExt
                val baseSourceName = if (outputExt != sourceExt)
                    sourceName.substringBeforeLast('.') + ".$outputExt"
                else sourceName
                val outputFileName = FileResolver.outputFileNameFromSource(baseSourceName)
                outputTarget = if (!outputFolderUri.isNullOrBlank()) {
                    FileResolver.createOutputTargetInTree(this, Uri.parse(outputFolderUri), outputFileName)
                } else {
                    FileResolver.createOutputTarget(this, uri, outputFileName, inputFile)
                }
                tempOutput = File(cacheDir, "processed_${System.currentTimeMillis()}.$outputExt")

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
                progress("Removing ${segments.size} sponsor segment(s) (~%.1fs) [${processingPlan.rationale}]".format(totalCut))

                FfmpegEngine.process(
                    inputFile, tempOutput,
                    sortedSegs.map { it.start to it.end },
                    cacheDir,
                    plan = processingPlan,
                    fileDurationSec = videoInfo.durationSec,
                    onProgress = { step -> progress(step) },
                    onProgressNumeric = { current, total ->
                        broadcastNumeric(current, total)
                        val notif = buildProgressNotification("SponsorCut", "Part $current/$total", current, total)
                        nm.notify(NOTIF_ID, notif)
                    }
                )

                val inputSizeMb = "%.2f".format(inputFile.length() / 1_000_000.0)
                progress("Saving output…")
                FileResolver.writeToOutputTarget(this, tempOutput, outputTarget)
                val outputSizeMb = "%.2f".format(tempOutput.length() / 1_000_000.0)

                val segSummary = sortedSegs.joinToString("\n") {
                    "  [${it.category}] %.1fs → %.1fs (%.1fs removed)".format(it.start, it.end, it.end - it.start)
                }

                // Duration-proportional expected size (rough sanity check)
                val keptSec = (videoInfo.durationSec ?: 0.0) - totalCut
                val totalSec = videoInfo.durationSec ?: 0.0
                val expectedRatio = if (totalSec > 0) keptSec / totalSec else 1.0
                val expectedMb = "%.2f".format(inputFile.length() * expectedRatio / 1_000_000.0)

                val sizeNote = if (processingPlan.canCopyVideo && processingPlan.canCopyAudio)
                    "expected ~${expectedMb}MB"
                else
                    "re-encoded; stream copy would be ~${expectedMb}MB"

                val result = "✓ Done — ID: $videoId\n\n" +
                    "Removed ${segments.size} segment(s) (~%.1fs):\n".format(totalCut) +
                    "$segSummary\n\n" +
                    "Media: ${videoInfo.summaryLine}\n" +
                    "Mode: ${processingPlan.rationale}\n\n" +
                    "Size: ${inputSizeMb}MB → ${outputSizeMb}MB ($sizeNote)\n\n" +
                    "Saved to:\n${outputTarget.label}"

                broadcast(text = result, done = true)
                showDoneNotification("SponsorCut done ✓", "Saved: ${outputTarget.label}")

            } catch (e: Exception) {
                Log.e(tag, "Processing failed", e)
                // Clean up the placeholder output file so no empty/partial file is left behind
                outputTarget?.let { FileResolver.deleteOutputTarget(this, it) }
                if (e.message == "CANCELLED") {
                    DiagLog.append("Service", "Job cancelled by user")
                    broadcast(text = "Job cancelled.", cancelled = true)
                    showDoneNotification("SponsorCut cancelled", "Job was cancelled by user.")
                } else {
                    DiagLog.append("Service", "FAILED: ${e.message}\n${e.stackTraceToString().take(800)}")
                    broadcast(text = "Failed: ${e.message ?: "unknown error"}", error = true)
                    showDoneNotification("SponsorCut failed", e.message ?: "unknown error")
                }
            } finally {
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
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

    private fun broadcast(text: String, done: Boolean = false, error: Boolean = false, cancelled: Boolean = false) {
        sendBroadcast(Intent(BROADCAST_PROGRESS).apply {
            `package` = packageName
            putExtra(EXTRA_PROGRESS_TEXT, text)
            putExtra(EXTRA_DONE, done)
            putExtra(EXTRA_ERROR, error)
            putExtra(EXTRA_CANCELLED, cancelled)
        })
    }

    private fun broadcastNumeric(current: Int, total: Int) {
        sendBroadcast(Intent(BROADCAST_PROGRESS).apply {
            `package` = packageName
            putExtra(EXTRA_PROGRESS_CURRENT, current)
            putExtra(EXTRA_PROGRESS_TOTAL, total)
        })
    }

    private fun buildProgressNotification(title: String, body: String, current: Int, total: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(body)
                .setProgress(total, current, false)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(body)
                .setProgress(total, current, false)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
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

