// app/src/main/java/com/sponsorcut/ProcessingService.kt

package com.sponsorcut

import com.sponsorcut.core.model.ExecutionMode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.sponsorcut.core.model.StreamSelection
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class ProcessingService : Service() {

    companion object {
        const val CHANNEL_ID = "sponsorcut_processing"
        const val NOTIF_ID = 1
        const val NOTIF_DONE_ID = 2
        const val ACTION_PROCESS = "com.sponsorcut.PROCESS"
        const val ACTION_CANCEL = "com.sponsorcut.CANCEL"
        const val EXTRA_URI = "uri"
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_OUTPUT_FOLDER_URI = "output_folder_uri"
        const val EXTRA_FRAME_ACCURATE = "frame_accurate"
        const val EXTRA_CATEGORIES = "categories"
        const val EXTRA_HW_ACCURATE = "hw_accurate"
        const val EXTRA_REMOVE_SEGMENTS = "remove_segments"
        const val EXTRA_LOCAL_SEGMENTS_JSON = "local_segments_json"
        const val EXTRA_SELECTED_SEGMENT_INDEXES = "selected_segment_indexes"
        const val EXTRA_STREAM_SELECTION = "stream_selection"
        const val EXTRA_INPUT_SUMMARY = "input_summary"
        const val EXTRA_SEGMENT_SOURCE_LABEL = "segment_source_label"
        const val EXTRA_OUTPUT_PREFERENCES_JSON = "output_preferences_json"

        const val BROADCAST_PROGRESS = "com.sponsorcut.PROGRESS"
        const val EXTRA_PROGRESS_TEXT = "progress_text"
        const val EXTRA_PROGRESS_CURRENT = "progress_current"
        const val EXTRA_PROGRESS_TOTAL = "progress_total"
        const val EXTRA_DONE = "done"
        const val EXTRA_ERROR = "error"
        const val EXTRA_CANCELLED = "cancelled"
        const val EXTRA_JOB_ID = "job_id"

        const val PREFS_NAME = "sponsorcut_prefs"
        const val KEY_LAST_OUTPUT_FILE_NAME = "last_output_file_name"

        private const val JOB_TAG = "ProcessingJob"

        /**
         * Authoritative single-job guard. A job ID is claimed before the
         * service is started and released exactly once when that job reaches
         * a terminal state. Every start path must claim it; nothing else may
         * clear it.
         */
        private val activeJobId = AtomicReference<String?>(null)

        /** Job whose worker thread has been launched; prevents duplicate threads per job. */
        private val runningJobId = AtomicReference<String?>(null)

        val isProcessingActive: Boolean
            get() = activeJobId.get() != null

        val currentJobId: String?
            get() = activeJobId.get()

        @Volatile
        private var cancelRequested = false

        /** Claims the single processing slot; returns null when another job owns it. */
        fun claimJob(): String? {
            val jobId = "job-" + UUID.randomUUID().toString().take(8)
            if (!activeJobId.compareAndSet(null, jobId)) {
                Log.w(JOB_TAG, "Claim rejected: active job=${activeJobId.get()}")
                return null
            }
            cancelRequested = false
            Log.i(JOB_TAG, "Claimed job=$jobId")
            return jobId
        }

        /** Releases the slot only if [jobId] still owns it; returns true exactly once per job. */
        fun releaseJob(jobId: String, reason: String): Boolean {
            clearIfOwned(runningJobId, jobId)
            val released = clearIfOwned(activeJobId, jobId)
            if (released) {
                Log.i(JOB_TAG, "Released job=$jobId reason=$reason")
                DiagLog.append("Job", "Released job=$jobId reason=$reason")
            } else {
                Log.w(
                    JOB_TAG,
                    "Release ignored for job=$jobId reason=$reason; active job=${activeJobId.get()}"
                )
                DiagLog.append(
                    "Job",
                    "Release ignored for job=$jobId reason=$reason; active job=${activeJobId.get()}"
                )
            }
            return released
        }

        /**
         * AtomicReference.compareAndSet compares by identity, but job IDs that
         * round-trip through an Intent are distinct String instances. Match by
         * value, then clear using the stored instance.
         */
        private fun clearIfOwned(ref: AtomicReference<String?>, jobId: String): Boolean {
            while (true) {
                val current = ref.get()
                if (current != jobId) return false
                if (ref.compareAndSet(current, null)) return true
            }
        }
    }

    private val tag = "ProcessingService"
    private lateinit var nm: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val elapsedHandler = Handler(Looper.getMainLooper())
    private var elapsedRunnable: Runnable? = null

    @Volatile
    private var lastProgressText = "Starting…"

    @Volatile
    private var segmentlessProcessingActive = false

    /** Job ID attached to every broadcast from this service instance. */
    @Volatile
    private var broadcastJobId: String? = null

    /** Terminal result held back until the job guard has been released. */
    @Volatile
    private var pendingTerminalIntent: Intent? = null

    private var jobStartElapsedRealtime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        try {
            startForeground(NOTIF_ID, buildNotification("Starting…"))
        } catch (e: Exception) {
            Log.e(tag, "startForeground failed", e)
        }
    }

    /** Every startForegroundService() call must be answered with startForeground(). */
    private fun ensureForeground() {
        try {
            startForeground(NOTIF_ID, buildNotification(lastProgressText))
        } catch (e: Exception) {
            Log.e(tag, "startForeground failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val requestedJob = intent.getStringExtra(EXTRA_JOB_ID)
            val activeJob = activeJobId.get()
            if (activeJob == null || (requestedJob != null && requestedJob != activeJob)) {
                Log.w(JOB_TAG, "Cancel ignored: requested job=$requestedJob active job=$activeJob")
                DiagLog.append("Job", "Cancel ignored: requested job=$requestedJob active job=$activeJob")
                return START_NOT_STICKY
            }
            Log.i(JOB_TAG, "Cancel requested: job=$activeJob")
            DiagLog.append("Job", "Cancel requested: job=$activeJob")
            cancelRequested = true
            com.arthenica.ffmpegkit.FFmpegKit.cancel()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_PROCESS) {
            if (!isProcessingActive) stopSelf(startId)
            return START_NOT_STICKY
        }

        // Accept only the job the caller claimed (or claim one here for callers
        // without an ID), and launch at most one worker per job.
        val requestedJobId = intent.getStringExtra(EXTRA_JOB_ID)
        val jobId = requestedJobId ?: claimJob()
        if (jobId == null || activeJobId.get() != jobId || !runningJobId.compareAndSet(null, jobId)) {
            Log.w(
                JOB_TAG,
                "Duplicate start rejected: requested job=$requestedJobId active job=${activeJobId.get()} " +
                    "running job=${runningJobId.get()} startId=$startId"
            )
            DiagLog.append(
                "Job",
                "Duplicate start rejected: requested job=$requestedJobId active job=${activeJobId.get()}"
            )
            ensureForeground()
            return START_NOT_STICKY
        }
        broadcastJobId = jobId
        pendingTerminalIntent = null
        lastProgressText = "Starting…"
        segmentlessProcessingActive = false
        ensureForeground()
        Log.i(JOB_TAG, "Start accepted: job=$jobId startId=$startId")

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString.isNullOrBlank()) {
            broadcast(text = "Failed: missing input URI", error = true)
            completeWithoutWorker(jobId, startId, "missing URI")
            return START_NOT_STICKY
        }

        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        val outputFolderUri = intent.getStringExtra(EXTRA_OUTPUT_FOLDER_URI)
        val frameAccurate = intent.getBooleanExtra(EXTRA_FRAME_ACCURATE, false)
        val categories = intent.getStringArrayListExtra(EXTRA_CATEGORIES)?.toList() ?: listOf("sponsor")
        val hwAccurate = intent.getBooleanExtra(EXTRA_HW_ACCURATE, false)
        val removeSegments = intent.getBooleanExtra(EXTRA_REMOVE_SEGMENTS, true)
        val localSegmentsJson = intent.getStringExtra(EXTRA_LOCAL_SEGMENTS_JSON)
        val selectedSegmentIndexes =
            intent.getIntArrayExtra(EXTRA_SELECTED_SEGMENT_INDEXES)?.toSet()
        val streamSelection = intent.getStringExtra(EXTRA_STREAM_SELECTION)
            ?.let { runCatching { StreamSelection.valueOf(it) }.getOrNull() }
            ?: StreamSelection.BOTH
        val inputSummary = intent.getStringExtra(EXTRA_INPUT_SUMMARY) ?: "unknown"
        val segmentSourceLabel = intent.getStringExtra(EXTRA_SEGMENT_SOURCE_LABEL)
            ?.takeUnless { it.isBlank() }
            ?: if (videoId.isNotBlank()) {
                "YouTube: https://youtu.be/$videoId"
            } else {
                "ID: Manual"
            }
        val outputPreferences = try {
            OutputPreferences.fromJson(intent.getStringExtra(EXTRA_OUTPUT_PREFERENCES_JSON))
        } catch (e: Exception) {
            Log.e(tag, "Invalid output preferences", e)
            broadcast(text = "Failed: invalid output preferences\n${e.message}", error = true)
            completeWithoutWorker(jobId, startId, "invalid output preferences")
            return START_NOT_STICKY
        }
        val uri = try { uriString.toUri() } catch (e: Exception) {
            broadcast(text = "Bad URI: $uriString\n${e.message}", error = true)
            completeWithoutWorker(jobId, startId, "bad URI")
            return START_NOT_STICKY
        }

        Thread {
            var terminalReason = "unknown"
            var outputCommitted = false
            jobStartElapsedRealtime = SystemClock.elapsedRealtime()
            startElapsedUpdates()
            cleanupPreviousRunArtifacts()
            var tempOutput: File? = null
            var cachedInput: File? = null
            var outputTarget: OutputTarget? = null
            // Acquire CPU wake lock — keeps processing running when screen turns off
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sponsorcut:processing")
                .also { it.acquire(2 * 60 * 60 * 1000L) }  // max 2 hours
            try {
                DiagLog.clear()
                DiagLog.append("Job", "Started job=$jobId startId=$startId")
                DiagLog.append("Input", "File: ${FileResolver.getDisplayName(this, uri)}\nMedia: $inputSummary")
                DiagLog.append("Service", "Starting. frameAccurate=$frameAccurate uri=$uriString")
                val power = collectPowerDiagnostics()
                DiagLog.append(
                    "Service",
                    "Power state: ${power.chargingState}; temp_c=${power.batteryTempC?.let { "%.1f".format(it) } ?: "unknown"}; " +
                        "power_save_mode=${power.powerSaveMode}; thermal_status=${power.thermalStatus}"
                )
                progress("Copying input video…")
                val inputFile = FileResolver.uriToFile(this, uri)
                cachedInput = inputFile
                DiagLog.append("Service", "Input cached: ${inputFile.absolutePath} size=${inputFile.length()}")

                if (cancelRequested) {
                    error("CANCELLED")
                }
                val sourceName = FileResolver.getDisplayName(this, uri)
                val plan = run {
                    // Need to inspect first to know if we're re-encoding (affects output extension)
                    progress("Inspecting video…")
                    val info = FFProbeInspector.inspect(inputFile)
                    DiagLog.append("FFProbe", "isTsEncapsulated=${info.isTsEncapsulated} codec=${info.videoCodec} audio=${info.audioCodec} summary=${info.summaryLine}")
                    TranscodePolicy.plan(
                        info = info,
                        frameAccurate = frameAccurate,
                        hwAccurate = hwAccurate,
                        removeSegments = removeSegments,
                        streamSelection = streamSelection,
                        preferences = outputPreferences,
                        catalog = EncoderCatalog.discover()
                    ).also { p ->
                        DiagLog.append("Plan", "canCopyVideo=${p.canCopyVideo} canCopyAudio=${p.canCopyAudio} useSlowSeek=${p.useSlowSeek} rationale=${p.rationale}")
                        progress("Plan: ${p.rationale}")
                    } to info
                }
                val (processingPlan, videoInfo) = plan

                // Force output container from FFprobe-driven policy, not input file naming.
                val outputExt = processingPlan.outputExtension
                val sourceBase = sourceName.substringBeforeLast('.', sourceName)
                val baseSourceName = "$sourceBase.$outputExt"
                val outputFileName = FileResolver.outputFileNameFromSource(baseSourceName)
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                    putString(KEY_LAST_OUTPUT_FILE_NAME, outputFileName)
                }
                outputTarget = if (!outputFolderUri.isNullOrBlank()) {
                    FileResolver.createOutputTargetInTree(this, outputFolderUri.toUri(), outputFileName)
                } else {
                    FileResolver.createOutputTarget(this, uri, outputFileName, inputFile)
                }
                DiagLog.append("Job", "job=$jobId owns output target: ${outputTarget.label}")
                tempOutput = File(cacheDir, "processed_${System.currentTimeMillis()}.$outputExt")
                DiagLog.append("Service", "Output container: .$outputExt muxer=${processingPlan.outputMuxer ?: "auto"}")

                if (!removeSegments) {
                    when (streamSelection) {
                        StreamSelection.AUDIO_ONLY -> progress("Extracting audio")
                        StreamSelection.VIDEO_ONLY -> progress("Extracting video")
                        StreamSelection.BOTH -> Unit
                    }

                    val encodeStartMs = System.currentTimeMillis()
                    segmentlessProcessingActive = streamSelection == StreamSelection.BOTH
                    val processingResult = try {
                        FfmpegEngine.process(
                            input = inputFile,
                            output = tempOutput,
                            segments = emptyList(),
                            cacheDir = cacheDir,
                            plan = processingPlan,
                            fileDurationSec = videoInfo.durationSec,
                            onProgress = { step -> progress(step) }
                        )
                    } finally {
                        segmentlessProcessingActive = false
                    }
                    val elapsedSeconds = (System.currentTimeMillis() - encodeStartMs) / 1000.0

                    progress("Saving output…")
                    FileResolver.writeToOutputTarget(this, tempOutput, outputTarget)
                    outputCommitted = true
                    DiagLog.append("Job", "job=$jobId output committed: ${outputTarget.label}")

                    val outputSizeMb = "%.2f".format(tempOutput.length() / 1_000_000.0)
                    val inputSizeMb = "%.2f".format(inputFile.length() / 1_000_000.0)

                    val executionMode = when {
                        hwAccurate -> ExecutionMode.HW_ACCURATE
                        frameAccurate -> ExecutionMode.SW_ACCURATE
                        else -> ExecutionMode.FAST
                    }

                    val outputInfo = FFProbeInspector.inspect(tempOutput)

                    val streamNote = when (streamSelection) {
                        StreamSelection.AUDIO_ONLY -> "Audio only"
                        StreamSelection.VIDEO_ONLY -> "Video only"
                        StreamSelection.BOTH -> "Video and audio"
                    }

                    val copyNote = buildString {
                        append(
                            when {
                                processingPlan.canCopyVideo && processingPlan.canCopyAudio ->
                                    "Video and audio stream-copied"
                                processingPlan.canCopyVideo -> "Video stream-copied; audio re-encoded"
                                processingPlan.canCopyAudio -> "Audio stream-copied; video re-encoded"
                                else -> "Video and audio re-encoded"
                            }
                        )
                    }

                    val audioBitrateNote = audioBitrateSummary(
                        processingPlan,
                        processingResult.audioRecoveryProfileUsed
                    )

                    val outputDurationNote = outputInfo.durationSec?.let {
                        "Output duration: ${formatElapsedHms(it)}"
                    } ?: "Output duration: unavailable"

                    val throughput = if (elapsedSeconds > 0.0) {
                        videoInfo.durationSec?.let {
                            "Throughput: x_realtime=${"%.3f".format(it / elapsedSeconds)}"
                        }
                    } else null

                    val completedActionLabel = when (streamSelection) {
                        StreamSelection.AUDIO_ONLY -> "Extracting audio"
                        StreamSelection.VIDEO_ONLY -> "Extracting video"
                        StreamSelection.BOTH -> "Processing media"
                    }
                    val result = "✓ $completedActionLabel complete\n\n" +
                        "Media: ${videoInfo.summaryLine}\n" +
                        "Mode: ${executionMode.name.replace('_', ' ')}\n" +
                        "Streams: $streamNote\n" +
                        "Processing: $copyNote\n" +
                        "Plan: ${processingPlan.rationale}\n" +
                        "Audio bitrate: $audioBitrateNote\n" +
                        "Output: ${outputTarget.label}\n" +
                        "Output container: ${processingPlan.outputExtension.uppercase()}\n" +
                        "$outputDurationNote\n" +
                        "Size: ${inputSizeMb}MB → ${outputSizeMb}MB\n" +
                        "Processing time: ${formatElapsedHms(elapsedSeconds)}" +
                        (throughput?.let { "\n$it" } ?: "")

                    DiagLog.setRunSummary(result)

                    broadcast(text = result, done = true)
                    showDoneNotification(
                        "SponsorCut done ✓",
                        "Saved: ${outputTarget.label}"
                    )
                    terminalReason = "done"
                    return@Thread
                }

                val segments = if (localSegmentsJson != null) {
                    progress("Loading local TXT/JSON segments…")

                    val parsed = mutableListOf<SponsorSegmentInfo>()
                    val array = org.json.JSONArray(localSegmentsJson)

                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        parsed += SponsorSegmentInfo(
                            category = obj.getString("category"),
                            start = obj.getDouble("start"),
                            end = obj.getDouble("end")
                        )
                    }

                    // The local/manual JSON payload already contains exactly
                    // the user's selected, edited visible segments. Do not apply
                    // category filtering or checkbox indexes again.
                    val selected = parsed
                    Log.i(
                        tag,
                        "Local/manual segments: payload=${parsed.size}, selected=${selected.size}"
                    )

                    if (selected.isEmpty()) {
                        broadcast(
                            text = "No selected categories match the loaded local segments. Nothing to remove.",
                            done = true
                        )
                        terminalReason = "nothing to remove"
                        return@Thread
                    }

                    selected
                } else {
                    progress("Fetching SponsorBlock segments…")
                    val fetched = SponsorBlockClient().fetchRich(videoId, categories)
                    Log.i(tag, "SponsorBlock result: $fetched")

                    if (fetched.isEmpty()) {
                        broadcast(
                            text = "SponsorBlock had no segments for ID: $videoId. Nothing to remove.",
                            done = true
                        )
                        terminalReason = "nothing to remove"
                        return@Thread
                    }

                    fetched
                }

                // Local/manual payloads are already the final selected set.
                // Apply checkbox indexes only to SponsorBlock results.
                val selectedSegments = if (localSegmentsJson != null) {
                    segments
                } else if (selectedSegmentIndexes == null) {
                    segments
                } else {
                    segments.filterIndexed { index, _ ->
                        index in selectedSegmentIndexes
                    }
                }

                if (selectedSegments.isEmpty()) {
                    broadcast(
                        text = "No individual segments were selected for removal. Nothing to do.",
                        done = true
                    )
                    terminalReason = "nothing to remove"
                    return@Thread
                }

                val sortedSegs = selectedSegments.sortedBy { it.start }
                val totalCut = sortedSegs.sumOf { it.end - it.start }
                progress("Removing ${segments.size} sponsor segment(s) (~%.1fs) [${processingPlan.rationale}]".format(totalCut))

                val encodeStartMs = System.currentTimeMillis()
                val encodeStats = FfmpegEngine.process(
                    inputFile, tempOutput,
                    sortedSegs.map { it.start to it.end },
                    cacheDir,
                    plan = processingPlan,
                    fileDurationSec = videoInfo.durationSec,
                    onProgress = { step -> progress(step) },
                    onProgressNumeric = { current, total ->
                        broadcastNumeric(current, total)
                        val notif = buildProgressNotification("Part $current/$total", current, total)
                        nm.notify(NOTIF_ID, notif)
                    }
                )
                val encodeElapsedSec = (System.currentTimeMillis() - encodeStartMs) / 1000.0

                // Log a full regression equation for fast mode (if available).
                if (!frameAccurate && !hwAccurate) {
                    val a = encodeStats.regressionA
                    val b = encodeStats.regressionB ?: 0.0
                    if (a != null) {
                        DiagLog.append(
                            "Perf",
                            "Fast mode regression: processing_time_s = ${"%.6f".format(a)} * (duration_time_s) + ${"%.6f".format(b)}"
                        )
                    } else {
                        DiagLog.append("Perf", "Fast mode regression unavailable (insufficient data points)")
                    }
                }

                val inputSizeMb = "%.2f".format(inputFile.length() / 1_000_000.0)
                progress("Saving output…")
                FileResolver.writeToOutputTarget(this, tempOutput, outputTarget)
                outputCommitted = true
                DiagLog.append("Job", "job=$jobId output committed: ${outputTarget.label}")
                val outputSizeMb = "%.2f".format(tempOutput.length() / 1_000_000.0)

                val outputInfo = FFProbeInspector.inspect(tempOutput)
                val executionMode = when {
                    hwAccurate -> ExecutionMode.HW_ACCURATE
                    frameAccurate -> ExecutionMode.SW_ACCURATE
                    else -> ExecutionMode.FAST
                }
                val requestedVideoBitrateBps =
                    outputPreferences.videoBitrate ?: videoInfo.videoBitrate
                val sizeAdvisory = TranscodePolicy.buildSizeAdvisory(
                    inputSizeBytes = inputFile.length(),
                    outputSizeBytes = tempOutput.length(),
                    inputDurationSec = videoInfo.durationSec,
                    outputDurationSec = outputInfo.durationSec,
                    requestedVideoBitrateBps = requestedVideoBitrateBps,
                    actualVideoBitrateBps = outputInfo.videoBitrate,
                    executionMode = executionMode
                )

                val segSummary = sortedSegs.joinToString("\n") {
                    "  [${it.category}] %s → %s (%.1fs rm)"
                        .format(
                            formatElapsedHms(it.start),
                            formatElapsedHms(it.end),
                            it.end - it.start
                        )
                }

                // Duration-proportional expected size (rough sanity check)
                val keptSec = (videoInfo.durationSec ?: 0.0) - totalCut
                val totalSec = videoInfo.durationSec ?: 0.0
                val expectedRatio = if (totalSec > 0) keptSec / totalSec else 1.0
                val expectedMb = "%.2f".format(inputFile.length() * expectedRatio / 1_000_000.0)

                val realtimeX = if (encodeElapsedSec > 0.0) keptSec / encodeElapsedSec else null
                DiagLog.append(
                    "Perf",
                    if (realtimeX != null)
                        "Throughput: x_realtime=${"%.3f".format(realtimeX)} (kept_duration_s=${"%.3f".format(keptSec)}, processing_time_s=${"%.3f".format(encodeElapsedSec)})"
                    else
                        "Throughput unavailable (processing_time_s=0)"
                )

                val sizeNote = if (processingPlan.canCopyVideo && processingPlan.canCopyAudio)
                    "expected ~${expectedMb}MB"
                else
                    "re-encoded; stream copy would be ~${expectedMb}MB"

                val equationLine = buildString {
                    val a = encodeStats.regressionA
                    val b = encodeStats.regressionB
                    if (processingPlan.singlePassFilter) {
                        // Single-pass: b not available; compute a = elapsed / kept_duration
                        val derivedA = if (keptSec > 0) encodeElapsedSec / keptSec else null
                        if (derivedA != null)
                            append("Rate equation: processing_time_s = ${"%.3f".format(derivedA)} * (duration_time_s)")
                    } else if (a != null) {
                        // Per-segment regression
                        val bVal = b ?: 0.0
                        append("Rate equation: processing_time_s = ${"%.3f".format(a)} * (duration_time_s) + ${"%.3f".format(bVal)}")
                    }
                }
                val timingLine = "Processing time: ${formatElapsedHms(encodeElapsedSec)}" +
                    (if (equationLine.isNotBlank()) "\n$equationLine" else "")

                val audioBitrateNote = audioBitrateSummary(
                    processingPlan,
                    encodeStats.audioRecoveryProfileUsed
                )
                val result = "✓ Done — $segmentSourceLabel\n\n" +
                    "Removed ${segments.size} segment(s) (~%.1fs):\n".format(totalCut) +
                    "$segSummary\n\n" +
                    "Media: ${videoInfo.summaryLine}\n" +
                    "Mode: ${processingPlan.rationale}\n" +
                    "Audio bitrate: $audioBitrateNote\n" +
                    "$timingLine\n\n" +
                    "Size: ${inputSizeMb}MB → ${outputSizeMb}MB ($sizeNote)\n\n" +
                    "Saved to:\n${outputTarget.label}" +
                    (sizeAdvisory?.let { "\n\n$it" } ?: "")

                DiagLog.setRunSummary(result)
                broadcast(text = result, done = true)
                showDoneNotification("SponsorCut done ✓", "Saved: ${outputTarget.label}")
                terminalReason = "done"

            } catch (e: Exception) {
                Log.e(tag, "Processing failed", e)
                if (e.message == "CANCELLED") {
                    terminalReason = "cancelled"
                    DiagLog.append("Service", "Job cancelled by user")
                    broadcast(text = "Job cancelled.", cancelled = true)
                    showDoneNotification("SponsorCut cancelled", "Job was cancelled by user.")
                } else {
                    terminalReason = "failed"
                    DiagLog.append("Service", "FAILED: ${e.message}\n${e.stackTraceToString().take(800)}")
                    broadcast(text = "Failed: ${e.message ?: "unknown error"}", error = true)
                    showDoneNotification("SponsorCut failed", e.message ?: "unknown error")
                }
            } finally {
                stopElapsedUpdates()
                cancelRequested = false
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                // Remove only this job's own placeholder/partial output. A
                // committed output is never deleted, even if a later step fails.
                val ownedTarget = outputTarget
                if (ownedTarget != null && !outputCommitted) {
                    DiagLog.append(
                        "Job",
                        "job=$jobId removing uncommitted output (reason=$terminalReason): ${ownedTarget.label}"
                    )
                    FileResolver.deleteOutputTarget(this, ownedTarget)
                }
                tempOutput?.delete()
                cachedInput?.delete()
                completeJob(jobId, startId, terminalReason)
            }
        }.start()

        return START_NOT_STICKY
    }

    /** Terminates a job that failed validation before its worker thread started. */
    private fun completeWithoutWorker(jobId: String, startId: Int, reason: String) {
        DiagLog.append("Job", "job=$jobId ended before worker start: $reason")
        completeJob(jobId, startId, reason)
    }

    /**
     * Releases the job guard exactly once, then announces the terminal result.
     * The broadcast is sent after release so the UI never re-enables processing
     * while the guard is still held.
     */
    private fun completeJob(jobId: String, startId: Int, reason: String) {
        val recorded = pendingTerminalIntent
        pendingTerminalIntent = null
        val terminal = recorded ?: Intent(BROADCAST_PROGRESS).apply {
            `package` = packageName
            putExtra(EXTRA_PROGRESS_TEXT, "Failed: processing ended unexpectedly ($reason)")
            putExtra(EXTRA_DONE, false)
            putExtra(EXTRA_ERROR, true)
            putExtra(EXTRA_CANCELLED, false)
            putExtra(EXTRA_JOB_ID, jobId)
        }
        DiagLog.append("Job", "job=$jobId terminal: $reason")
        // Re-persist so a paused Activity restores the complete log, including
        // the job-terminal diagnostics appended after the result was recorded.
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            if (recorded == null) {
                putString("last_completed_status", terminal.getStringExtra(EXTRA_PROGRESS_TEXT))
            }
            putString("last_completed_log", DiagLog.get())
        }
        releaseJob(jobId, reason)
        finish(startId)
        sendBroadcast(terminal)
    }

    private fun cleanupPreviousRunArtifacts() {
        val prefixes = listOf("input_", "part_", "concat_", "singlepass_", "processed_")
        val removed = mutableListOf<String>()

        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && prefixes.any { file.name.startsWith(it) }) {
                if (file.delete()) removed += file.name
            }
        }

        if (removed.isNotEmpty()) {
            DiagLog.append(
                "Cleanup",
                "Removed ${removed.size} stale temporary artifact(s): ${removed.joinToString(", ")}"
            )
        }
    }

    private fun finish(startId: Int) {
        // Runs on the main thread, serialized with job claims and
        // onStartCommand, so a newer job's foreground state is never removed.
        elapsedHandler.post {
            if (isProcessingActive) {
                Log.i(JOB_TAG, "Teardown skipped for startId=$startId; active job=${currentJobId}")
                return@post
            }
            // Remove the active foreground/progress notification. The separate
            // completion notification uses NOTIF_DONE_ID and must remain visible.
            runCatching {
                nm.cancel(NOTIF_ID)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun progress(text: String) {
        lastProgressText = text
        nm.notify(NOTIF_ID, buildNotification(text))
        broadcast(text = text)
    }

    private fun startElapsedUpdates() {
        stopElapsedUpdates()

        val runnable = object : Runnable {
            override fun run() {
                if (!isProcessingActive) return

                val body = if (segmentlessProcessingActive) {
                    val elapsedSeconds =
                        (SystemClock.elapsedRealtime() - jobStartElapsedRealtime) / 1000.0
                    "$lastProgressText\nElapsed: ${formatElapsedHms(elapsedSeconds)}"
                } else {
                    lastProgressText
                }

                runCatching {
                    nm.notify(
                        NOTIF_ID,
                        buildNotification(body)
                    )
                }

                elapsedHandler.postDelayed(this, 1_000L)
            }
        }

        elapsedRunnable = runnable
        elapsedHandler.post(runnable)
    }

    private fun stopElapsedUpdates() {
        elapsedRunnable?.let { elapsedHandler.removeCallbacks(it) }
        elapsedRunnable = null
    }

    private fun broadcast(text: String, done: Boolean = false, error: Boolean = false, cancelled: Boolean = false) {
        // Persist terminal results before announcing them. MainActivity may be
        // paused and have unregistered its receiver, so it can otherwise miss
        // the completion broadcast and restore only the last progress message.
        if (done || error || cancelled) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                putString("last_completed_status", text)
                putString("last_completed_log", DiagLog.get())
            }
        }

        val intent = Intent(BROADCAST_PROGRESS).apply {
            `package` = packageName
            putExtra(EXTRA_PROGRESS_TEXT, text)
            putExtra(EXTRA_DONE, done)
            putExtra(EXTRA_ERROR, error)
            putExtra(EXTRA_CANCELLED, cancelled)
            putExtra(EXTRA_JOB_ID, broadcastJobId)
        }
        if (done || error || cancelled) {
            // Delivered by completeJob() after the job guard is released.
            pendingTerminalIntent = intent
            return
        }
        sendBroadcast(intent)
    }

    private fun broadcastNumeric(current: Int, total: Int) {
        sendBroadcast(Intent(BROADCAST_PROGRESS).apply {
            `package` = packageName
            putExtra(EXTRA_PROGRESS_CURRENT, current)
            putExtra(EXTRA_PROGRESS_TOTAL, total)
        })
    }

    private fun buildProgressNotification(body: String, current: Int, total: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("SponsorCut")
            .setContentText(body)
            .setProgress(total, current, false)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "SponsorCut", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Video processing progress"
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(body: String?): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("SponsorCut")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun showDoneNotification(title: String, body: String) {
        val pi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_DONE_ID, n)
    }

    private data class PowerDiagnostics(
        val chargingState: String,
        val batteryTempC: Double?,
        val powerSaveMode: Boolean,
        val thermalStatus: String
    )

    private fun collectPowerDiagnostics(): PowerDiagnostics {
        var chargingState = "unknown"
        var batteryTempC: Double? = null

        try {
            val batteryIntent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val tempTenths = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (tempTenths != Int.MIN_VALUE) batteryTempC = tempTenths / 10.0

                val source = when {
                    plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "ac"
                    plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "usb"
                    plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "wireless"
                    plugged and BatteryManager.BATTERY_PLUGGED_DOCK != 0 -> "dock"
                    else -> "unplugged"
                }

                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                chargingState = if (charging) "charging($source)" else "not_charging"
            }
        } catch (_: Exception) {
            chargingState = "unknown"
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val powerSaveMode = runCatching { pm.isPowerSaveMode }.getOrDefault(false)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { thermalStatusLabel(pm.currentThermalStatus) }.getOrElse { "unknown" }
        } else {
            "unsupported"
        }

        return PowerDiagnostics(
            chargingState = chargingState,
            batteryTempC = batteryTempC,
            powerSaveMode = powerSaveMode,
            thermalStatus = thermalStatus
        )
    }

    private fun thermalStatusLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown($status)"
    }

    private fun audioBitrateSummary(
        plan: TranscodePolicy.ProcessingPlan,
        audioRecoveryProfileUsed: Boolean
    ): String {
        val effectiveBitrate = plan.audioBitrateBps?.let(::formatBitrateKbps)
        val requestedBitrate = plan.requestedAudioBitrateBps?.let(::formatBitrateKbps)
        val planned = when {
            plan.dropAudio -> "not applicable"
            plan.canCopyAudio -> "input/inherited (stream copy)"
            else -> when (plan.audioBitrateProvenance) {
                TranscodePolicy.AudioBitrateProvenance.STREAM_COPY ->
                    "input/inherited (stream copy)"
                TranscodePolicy.AudioBitrateProvenance.AUTO_SOURCE ->
                    "$effectiveBitrate (Input/Auto; source bitrate)"
                TranscodePolicy.AudioBitrateProvenance.POLICY_FALLBACK ->
                    "$effectiveBitrate (128k - policy fallback)"
                TranscodePolicy.AudioBitrateProvenance.EXPLICIT ->
                    "$effectiveBitrate (explicit)"
                TranscodePolicy.AudioBitrateProvenance.EXPLICIT_BELOW_MINIMUM_SOURCE ->
                    "$effectiveBitrate (source bitrate; explicit $requestedBitrate below minimum)"
                TranscodePolicy.AudioBitrateProvenance.EXPLICIT_BELOW_MINIMUM_FALLBACK ->
                    "$effectiveBitrate (policy fallback; explicit $requestedBitrate below minimum)"
                TranscodePolicy.AudioBitrateProvenance.NONE ->
                    "not specified by the selected encoder"
            }
        }

        return if (audioRecoveryProfileUsed) {
            "$planned; AAC recovery profile used: 128k"
        } else {
            planned
        }
    }

    private fun formatElapsedHms(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return "${h}h${m}m${s}s"
    }
}
