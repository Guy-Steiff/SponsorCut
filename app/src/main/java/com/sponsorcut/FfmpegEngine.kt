package com.sponsorcut

import com.arthenica.ffmpegkit.FFmpegKit
import com.sponsorcut.core.model.JobKind
import com.sponsorcut.core.model.StreamSelection
import com.arthenica.ffmpegkit.ReturnCode
import android.util.Log
import java.io.File

/**
 * Execution layer only.
 * Receives a ProcessingPlan from TranscodePolicy and carries it out.
 * Never calls ffprobe directly — use FFProbeInspector for that.
 */
/** Regression stats returned by FfmpegEngine.process() for the summary screen. */
data class ProcessingResult(
    /** processing_time = a × duration + b  (per-segment regression) */
    val regressionA: Double? = null,
    /** Fixed overhead per segment (seconds). Null for single-pass or < 2 data points. */
    val regressionB: Double? = null
)

object FfmpegEngine {

    private const val TAG = "FfmpegEngine"

    fun process(
        input: File,
        output: File,
        segments: List<Pair<Double, Double>>,
        cacheDir: File,
        plan: TranscodePolicy.ProcessingPlan,
        fileDurationSec: Double? = null,
        onProgress: ((text: String) -> Unit)? = null,
        onProgressNumeric: ((current: Int, total: Int) -> Unit)? = null
    ): ProcessingResult {
        if (!plan.removeSegments) {
            processStreamSelection(input, output, plan, onProgress)
            return ProcessingResult()
        }

        if (segments.isEmpty()) return ProcessingResult()

        Log.i(TAG, "Input: ${input.absolutePath} exists=${input.exists()} size=${input.length()} plan.useSlowSeek=${plan.useSlowSeek} plan.canCopyVideo=${plan.canCopyVideo}")
        DiagLog.append("Engine", "Input exists=${input.exists()} size=${input.length()} useSlowSeek=${plan.useSlowSeek} canCopyVideo=${plan.canCopyVideo} canCopyAudio=${plan.canCopyAudio}")

        val sorted = segments.sortedBy { it.first }
        val keepRanges = mutableListOf<Pair<Double, Double>>()
        var lastEnd = 0.0
        for ((start, end) in sorted) {
            if (start > lastEnd + 0.01) keepRanges += lastEnd to start
            if (end > lastEnd) lastEnd = end
        }
        keepRanges += lastEnd to Double.MAX_VALUE

        Log.d(TAG, "Keep ranges (${keepRanges.size}): $keepRanges | plan: ${plan.rationale}")

        // ── Single-pass path (HW-Accurate) ───────────────────────────────────
        // Builds one filter_complex with trim+setpts+concat, runs a single FFmpeg
        // command so h264_mediacodec is initialised exactly once across all segments.
        if (plan.singlePassFilter) {
            processSinglePass(input, output, keepRanges, plan, fileDurationSec, onProgress, onProgressNumeric)
            return ProcessingResult()  // regression computed in ProcessingService for single-pass
        }

        val parts = mutableListOf<File>()
        val total = keepRanges.size
        val jobStartMs = System.currentTimeMillis()
        var completedCount = 0

        var regN  = 0
        var regSX = 0.0
        var regSY = 0.0
        var regSXY= 0.0
        var regSX2= 0.0

        // Force container based on probe-derived output extension, not input file naming.
        val ext = output.extension.ifBlank { plan.outputExtension.ifBlank { "mp4" } }

        try {
            for ((idx, range) in keepRanges.withIndex()) {
                val (keepStart, keepEnd) = range
                val duration: Double? = when {
                    keepEnd != Double.MAX_VALUE -> keepEnd - keepStart
                    fileDurationSec != null -> (fileDurationSec - keepStart).coerceAtLeast(0.1)
                    else -> null
                }

                if (duration != null && duration <= 0.01) {
                    Log.d(TAG, "Skipping zero-length range $keepStart->$keepEnd")
                    continue
                }

                val partFile = File(cacheDir, "part_${idx}_${System.currentTimeMillis()}.$ext")

                val elapsed = System.currentTimeMillis() - jobStartMs

                val (regA, regB) = when {
                    regN == 0 -> null to null
                    regN == 1 -> (regSY / regSX) to 0.0
                    else -> {
                        val denom = regN * regSX2 - regSX * regSX
                        if (denom == 0.0) null to null
                        else {
                            val a = (regN * regSXY - regSX * regSY) / denom
                            val b = (regSY - a * regSX) / regN
                            a to b
                        }
                    }
                }
                val etaMs: Long? = if (regA != null && regB != null) {
                    val remainingDuration = keepRanges
                        .drop(idx)
                        .mapNotNull { (s, e) ->
                            when {
                                e != Double.MAX_VALUE -> e - s
                                fileDurationSec != null -> (fileDurationSec - s).coerceAtLeast(0.1)
                                else -> null
                            }
                        }
                        .sum()
                    val remainingSegments = keepRanges.drop(idx).size
                    val predictedSec = regA * remainingDuration + regB * remainingSegments
                    (predictedSec * 1000).toLong().coerceAtLeast(0L)
                } else null

                val stats = if (completedCount > 0) buildString {
                    append("${completedCount}/$total done [${formatDuration(elapsed)} elapsed")
                    if (etaMs != null) append(", ~${formatDuration(etaMs)} left")
                    append("]")
                } else "${formatDuration(elapsed)} elapsed"
                val label = "Part ${idx + 1}/$total: ${formatTimestamp(keepStart)} → " +
                    "${if (keepEnd == Double.MAX_VALUE) "end" else formatTimestamp(keepEnd)}\n$stats"
                onProgressNumeric?.invoke(idx, total)
                onProgress?.invoke(label)

                val segStartMs = System.currentTimeMillis()

                // Capture values for ticker (immutable snapshot at segment start)
                val tickEtaMs    = etaMs
                val tickCompleted = completedCount
                val rangeLabel = "${formatTimestamp(keepStart)} → " +
                    if (keepEnd == Double.MAX_VALUE) "end" else formatTimestamp(keepEnd)

                // Per-second live ticker: increments elapsed, decrements ETA within the segment
                val tickerActive = java.util.concurrent.atomic.AtomicBoolean(true)
                val tickerThread = Thread {
                    var ticks = 0
                    while (tickerActive.get()) {
                        try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                        ticks++
                        if (!tickerActive.get()) break
                        val nowElapsed  = System.currentTimeMillis() - jobStartMs
                        val adjustedEta = if (tickEtaMs != null)
                            (tickEtaMs - ticks * 1000L).coerceAtLeast(0L) else null
                        val tickStats = if (tickCompleted > 0) buildString {
                            append("${tickCompleted}/$total done [${formatDuration(nowElapsed)} elapsed")
                            if (adjustedEta != null) append(", ~${formatDuration(adjustedEta)} left")
                            append("]")
                        } else "${formatDuration(nowElapsed)} elapsed"
                        onProgress?.invoke("Part ${idx + 1}/$total: $rangeLabel\n$tickStats")
                    }
                }
                tickerThread.isDaemon = true
                tickerThread.start()

                var rc = executeSegment(
                    input, partFile, keepStart, duration, plan,
                    slowSeek = false,
                    useFallbackVideoArgs = false,
                    useFallbackAudioArgs = false
                )
                tickerActive.set(false)
                tickerThread.join(200)

                if (rc == RETURN_CANCEL) error("CANCELLED")

                if (rc != RETURN_SUCCESS && !plan.canCopyVideo) {
                    DiagLog.append("Engine", "Part $idx fast-seek rc=1, retrying with slow seek")
                    onProgress?.invoke("Part ${idx + 1}/$total: retrying with slow seek…")
                    partFile.delete()

                    // Restart ticker for the slow-seek retry
                    val retryTicker = java.util.concurrent.atomic.AtomicBoolean(true)
                    val retryThread = Thread {
                        var ticks = 0
                        while (retryTicker.get()) {
                            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                            ticks++
                            if (!retryTicker.get()) break
                            val nowElapsed  = System.currentTimeMillis() - jobStartMs
                            val adjustedEta = if (tickEtaMs != null)
                                (tickEtaMs - ticks * 1000L).coerceAtLeast(0L) else null
                            val tickStats = if (tickCompleted > 0) buildString {
                                append("${tickCompleted}/$total done [${formatDuration(nowElapsed)} elapsed")
                                if (adjustedEta != null) append(", ~${formatDuration(adjustedEta)} left")
                                append("]")
                            } else "${formatDuration(nowElapsed)} elapsed"
                            onProgress?.invoke("Part ${idx + 1}/$total: $rangeLabel (slow seek retry)\n$tickStats")
                        }
                    }
                    retryThread.isDaemon = true
                    retryThread.start()
                    rc = executeSegment(
                        input, partFile, keepStart, duration, plan,
                        slowSeek = true,
                        useFallbackVideoArgs = false,
                        useFallbackAudioArgs = false
                    )
                    retryTicker.set(false)
                    retryThread.join(200)
                    if (rc == RETURN_CANCEL) error("CANCELLED")
                }

                val hasFallbackProfile =
                    (!plan.canCopyVideo && plan.fallbackVideoEncoderArgs != plan.videoEncoderArgs) ||
                    (!plan.canCopyAudio && plan.fallbackAudioEncoderArgs != plan.audioEncoderArgs)
                if (rc != RETURN_SUCCESS && hasFallbackProfile) {
                    DiagLog.append("Engine", "Part $idx retrying with conservative fallback encoder profile")
                    onProgress?.invoke("Part ${idx + 1}/$total: retrying with conservative fallback profile…")
                    partFile.delete()
                    rc = executeSegment(
                        input, partFile, keepStart, duration, plan,
                        slowSeek = true,
                        useFallbackVideoArgs = true,
                        useFallbackAudioArgs = true
                    )
                    if (rc == RETURN_CANCEL) error("CANCELLED")
                }

                // HW-Accurate: do NOT silently fall back — surface the error for the user to see.
                if (rc != RETURN_SUCCESS && plan.hwDecoderArgs.isNotEmpty()) {
                    DiagLog.append("Engine", "Part $idx HW pipeline failed (rc=$rc). No SW fallback in HW-Accurate mode.")
                    error("HW-Accurate failed on part ${idx + 1} (rc=$rc).\n\nYour device may not support this codec via MediaCodec.\nPlease retry with SW-Accurate or Fast mode and check the diagnostic log.")
                }

                if (rc != RETURN_SUCCESS) error("FFmpeg trim part $idx failed (rc=$rc)")

                if (partFile.exists() && partFile.length() > 0) {
                    parts += partFile
                    completedCount++

                    if (duration != null && duration > 0.01) {
                        val processingTimeSec = (System.currentTimeMillis() - segStartMs) / 1000.0
                        regN++
                        regSX  += duration
                        regSY  += processingTimeSec
                        regSXY += duration * processingTimeSec
                        regSX2 += duration * duration
                        val logMsg = if (regN == 1) {
                            "a=%.4f b=0 (1-point, origin)".format(regSY / regSX)
                        } else {
                            val denom = regN * regSX2 - regSX * regSX
                            val a = if (denom != 0.0) (regN * regSXY - regSX * regSY) / denom else 0.0
                            val b = (regSY - a * regSX) / regN
                            "a=%.4f b=%.4f (n=$regN)".format(a, b)
                        }
                        DiagLog.append("Engine", "Regression: duration=%.2fs proc=%.2fs $logMsg".format(duration, processingTimeSec))
                    }

                    onProgressNumeric?.invoke(idx + 1, total)
                    onProgress?.invoke("Part ${idx + 1}/$total done ✓")
                } else {
                    Log.w(TAG, "Part $idx empty, skipping")
                }
            }

            if (parts.isEmpty()) error("No output parts produced")

            if (parts.size == 1) {
                onProgress?.invoke("Finalising...")
                onProgressNumeric?.invoke(total, total)
                parts[0].copyTo(output, overwrite = true)
                return buildRegressionResult(regN, regSX, regSY, regSXY, regSX2)
            }

            onProgress?.invoke("Merging ${parts.size} parts...")
            val concatFile = File(cacheDir, "concat_${System.currentTimeMillis()}.txt")
            concatFile.writeText(parts.joinToString("\n") { "file '${it.absolutePath}'" })
            val concatOut = File(cacheDir, "concat_out_${System.currentTimeMillis()}.$ext")
            val concatArgs = "-y -f concat -safe 0 -i ${concatFile.absolutePath} -c copy " +
                "${plan.outputMuxer?.let { "-f $it " } ?: ""}${concatOut.absolutePath}"
            Log.d(TAG, "Concat args: $concatArgs")
            val session = FFmpegKit.execute(concatArgs)
            concatFile.delete()
            if (ReturnCode.isCancel(session.returnCode)) error("CANCELLED")
            if (!ReturnCode.isSuccess(session.returnCode)) error("FFmpeg concat failed (rc=${session.returnCode})")
            concatOut.copyTo(output, overwrite = true)
            concatOut.delete()
            onProgressNumeric?.invoke(total, total)

        } finally {
            parts.forEach { it.delete() }
        }
        return buildRegressionResult(regN, regSX, regSY, regSXY, regSX2)
    }

    private fun buildRegressionResult(regN: Int, regSX: Double, regSY: Double, regSXY: Double, regSX2: Double): ProcessingResult {
        if (regN < 1) return ProcessingResult()
        if (regN == 1) return ProcessingResult(regressionA = if (regSX > 0) regSY / regSX else null)
        val denom = regN * regSX2 - regSX * regSX
        if (denom == 0.0) return ProcessingResult()
        val a = (regN * regSXY - regSX * regSY) / denom
        val b = (regSY - a * regSX) / regN
        return ProcessingResult(regressionA = a, regressionB = b)
    }

    private const val RETURN_SUCCESS = 0
    private const val RETURN_CANCEL = 255

    // Elapsed-time display: "1m30s", "2h05m"
    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60   -> "${s}s"
            s < 3600 -> "%dm%02ds".format(s / 60, s % 60)
            else     -> "%dh%02dm".format(s / 3600, (s % 3600) / 60)
        }
    }

    // Similar helper exists in MainActivity (formatSegmentTimestamp), but engine progress uses
    // compact m:ss/h:mm:ss labels to keep frequent status updates readable during processing.
    private fun formatTimestamp(sec: Double): String {
        val total = sec.toLong().coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
               else       "%d:%02d".format(m, s)
    }

    private fun runExtraction(
        input: File,
        output: File,
        plan: TranscodePolicy.ProcessingPlan,
        onProgress: ((String) -> Unit)?,
        label: String
    ) {
        val args = mutableListOf("-y", "-i", input.absolutePath)

        if (plan.streamSelection == StreamSelection.AUDIO_ONLY) {
            args += "-vn"
            args += if (plan.canCopyAudio) {
                listOf("-c:a", "copy")
            } else {
                plan.audioEncoderArgs
            }
        } else {
            args += "-an"
            args += if (plan.canCopyVideo) {
                listOf("-c:v", "copy")
            } else {
                plan.videoEncoderArgs
            }
        }

        plan.outputMuxer?.let { args += listOf("-f", it) }
        args += output.absolutePath

        val command = args.joinToString(" ")
        Log.i(TAG, "$label args: $command")
        DiagLog.append("Engine", "$label args: $command")
        onProgress?.invoke("$label…")

        val session = FFmpegKit.execute(command)
        val rc = session.returnCode

        when {
            ReturnCode.isCancel(rc) -> {
                output.delete()
                error("CANCELLED")
            }
            !ReturnCode.isSuccess(rc) -> {
                val rcValue = rc?.value ?: -1
                DiagLog.append("Engine", "$label FAILED rc=$rcValue")
                output.delete()
                error("$label failed (rc=$rcValue)")
            }
            else -> {
                onProgress?.invoke("$label complete")
            }
        }
    }

    private fun processStreamSelection(
        input: File,
        output: File,
        plan: TranscodePolicy.ProcessingPlan,
        onProgress: ((String) -> Unit)?
    ) {
        if (plan.streamSelection == StreamSelection.BOTH) {
            processBothNoSegments(
                input = input,
                output = output,
                plan = plan,
                onProgress = onProgress
            )
            return
        }

        val label = when (plan.streamSelection) {
            StreamSelection.AUDIO_ONLY ->
                if (plan.canCopyAudio) "Extracting audio"
                else "Extracting and transcoding audio"
            StreamSelection.VIDEO_ONLY ->
                if (plan.canCopyVideo) "Extracting video"
                else "Extracting and transcoding video"
            StreamSelection.BOTH -> "Processing media"
        }

        runExtraction(
            input = input,
            output = output,
            plan = plan,
            onProgress = onProgress,
            label = label
        )
    }

    private fun processBothNoSegments(
        input: File,
        output: File,
        plan: TranscodePolicy.ProcessingPlan,
        onProgress: ((String) -> Unit)?
    ) {
        val args = mutableListOf("-y", "-i", input.absolutePath)

        if (plan.canCopyVideo) {
            args += listOf("-c:v", "copy")
        } else {
            args += plan.videoEncoderArgs
        }

        if (plan.canCopyAudio) {
            args += listOf("-c:a", "copy")
        } else {
            args += plan.audioEncoderArgs
        }

        plan.outputMuxer?.let { args += listOf("-f", it) }
        args += output.absolutePath

        val command = args.joinToString(" ")
        Log.i(TAG, "Processing media args: $command")
        DiagLog.append("Engine", "Processing media args: $command")
        onProgress?.invoke("Processing media…")

        val session = FFmpegKit.execute(command)
        val rc = session.returnCode

        when {
            ReturnCode.isCancel(rc) -> {
                output.delete()
                error("CANCELLED")
            }
            !ReturnCode.isSuccess(rc) -> {
                val rcValue = rc?.value ?: -1
                DiagLog.append("Engine", "Processing media FAILED rc=$rcValue")
                output.delete()
                error("Processing media failed (rc=$rcValue)")
            }
            else -> {
                onProgress?.invoke("Processing media complete")
            }
        }
    }

    /**
     * Processes all keepRanges in a single FFmpeg filter_complex command.
     * h264_mediacodec is initialised once → eliminates per-segment init overhead.
     */
    /**
     * HW-Accurate single-pass: each keep range gets its own seeked input so the decoder
     * jumps directly to the right position — no wasted decoding of discarded frames.
     * The h264_mediacodec encoder is initialised exactly once for all segments combined.
     *
     * e.g. 2 keep ranges → command:
     *   ffmpeg -y -i input -ss 449.236 -i input
     *     -filter_complex "[0:v]trim=end=395.883,...[v0]; [0:a]...[a0];
     *                      [1:v]setpts=PTS-STARTPTS[v1]; [1:a]...[a1];
     *                      [v0][a0][v1][a1]concat=n=2:v=1:a=1[outv][outa]"
     *     -map [outv] -map [outa] -c:v h264_mediacodec -b:v Nk -c:a aac -b:a 128k out.mp4
     */
        private fun validFrameRate(value: String?): String? {
        return value
            ?.trim()
            ?.takeIf { it.matches(Regex("\\d+(?:\\.\\d+)?")) }
    }

private fun validateSinglePassGraph(
        filters: List<String>,
        expectedBranchCount: Int,
        slowSeek: Boolean,
        isAudioOnly: Boolean,
        keepAudio: Boolean
    ) {
        val graph = filters.joinToString(";")

        // Validate concat wiring for both Fast and slow-seek modes.
        require(graph.contains("concat=n=$expectedBranchCount:")) {
            "Single-pass graph invalid: expected concat n=$expectedBranchCount. Graph=$graph"
        }

        if (slowSeek) {
            // Slow-seek mode does not pre-seek inputs with -ss. Therefore every
            // branch must trim from absolute source timestamps. A bare
            // setpts/asetpts branch would consume the entire source and duplicate
            // it during concat.
            if (!isAudioOnly) {
                val videoTrimCount = Regex("""\[\d+:v\]trim=""")
                    .findAll(graph)
                    .count()

                require(videoTrimCount == expectedBranchCount) {
                    "Single-pass slow-seek graph invalid: expected $expectedBranchCount " +
                        "video trim branches, found $videoTrimCount. Graph=$graph"
                }

                val untrimmedVideoBranch = Regex("""\[\d+:v\]setpts=""")
                    .containsMatchIn(graph)

                require(!untrimmedVideoBranch) {
                    "Single-pass slow-seek graph contains an untrimmed video branch. Graph=$graph"
                }
            }

            if (keepAudio) {
                val audioTrimCount = Regex("""\[\d+:a\]atrim=""")
                    .findAll(graph)
                    .count()

                require(audioTrimCount == expectedBranchCount) {
                    "Single-pass slow-seek graph invalid: expected $expectedBranchCount " +
                        "audio trim branches, found $audioTrimCount. Graph=$graph"
                }

                val untrimmedAudioBranch = Regex("""\[\d+:a\]asetpts=""")
                    .containsMatchIn(graph)

                require(!untrimmedAudioBranch) {
                    "Single-pass slow-seek graph contains an untrimmed audio branch. Graph=$graph"
                }
            }
        } else {
            // Fast mode pre-seeks each input with -ss. A finite range uses trim
            // relative to the seeked input; the final EOF range may legitimately
            // use bare setpts/asetpts because its input begins at the requested
            // seek position.
            if (!isAudioOnly) {
                val videoBranchCount = Regex("""\[\d+:v\](?:trim=|setpts=)""")
                    .findAll(graph)
                    .count()

                require(videoBranchCount == expectedBranchCount) {
                    "Single-pass Fast graph invalid: expected $expectedBranchCount " +
                        "video branches, found $videoBranchCount. Graph=$graph"
                }
            }

            if (keepAudio) {
                val audioBranchCount = Regex("""\[\d+:a\](?:atrim=|asetpts=)""")
                    .findAll(graph)
                    .count()

                require(audioBranchCount == expectedBranchCount) {
                    "Single-pass Fast graph invalid: expected $expectedBranchCount " +
                        "audio branches, found $audioBranchCount. Graph=$graph"
                }
            }
        }
    }

private fun processSinglePass(
        input: File,
        output: File,
        keepRanges: List<Pair<Double, Double>>,
        plan: TranscodePolicy.ProcessingPlan,
        fileDurationSec: Double?,
        onProgress: ((String) -> Unit)?,
        onProgressNumeric: ((Int, Int) -> Unit)?
    ) {
        val isAudioOnly = plan.dropVideo
        val keepAudio = !plan.dropAudio
        val ext = output.extension.ifBlank { plan.outputExtension.ifBlank { "mp4" } }
        val tempOut = File(output.parent ?: output.absolutePath, "singlepass_${System.currentTimeMillis()}.$ext")

        fun buildSinglePassArgs(useFallbackProfile: Boolean): List<String> {
            val args = mutableListOf("-y")
            val slowSeek = plan.useSlowSeek

            // Fast path: each keep range gets its own input-level seek. This is
            // fast, but MPEG-TS can land video and audio at different demux
            // points, producing A/V boundary drift.
            //
            // Slow/TS path: do not pre-seek. Trim video and audio from the same
            // absolute source timestamps so their kept ranges cannot diverge.
            for ((start, _) in keepRanges) {
                if (!slowSeek && start > 0.001) {
                    args += listOf("-ss", "%.3f".format(start))
                }
                args += listOf("-i", input.absolutePath)
            }

            val videoFilters = mutableListOf<String>()
            val audioFilters = mutableListOf<String>()
            for ((idx, range) in keepRanges.withIndex()) {
                val (start, end) = range
                val duration = if (end == Double.MAX_VALUE) null else end - start

                DiagLog.append(
                    "Engine",
                    "Single-pass branch $idx: " +
                        "start=$start, " +
                        "end=${if (end == Double.MAX_VALUE) "EOF" else end}, " +
                        "duration=${duration ?: "EOF"}, " +
                        "slowSeek=$slowSeek, " +
                        "isFinal=${idx == keepRanges.lastIndex}"
                )

                if (!isAudioOnly) {
                    videoFilters += when {
                        slowSeek && duration != null ->
                            "[$idx:v]trim=start=%.3f:end=%.3f,setpts=PTS-STARTPTS[v$idx]".format(start, end)
                        slowSeek ->
                            "[$idx:v]trim=start=%.3f,setpts=PTS-STARTPTS[v$idx]".format(start)
                        duration != null ->
                            "[$idx:v]trim=start=0:end=%.3f,setpts=PTS-STARTPTS[v$idx]".format(duration)
                        else ->
                            "[$idx:v]setpts=PTS-STARTPTS[v$idx]"
                    }
                }

                audioFilters += when {
                    slowSeek && duration != null ->
                        "[$idx:a]atrim=start=%.3f:end=%.3f,asetpts=PTS-STARTPTS[a$idx]".format(start, end)
                    slowSeek ->
                        "[$idx:a]atrim=start=%.3f,asetpts=PTS-STARTPTS[a$idx]".format(start)
                    duration != null ->
                        "[$idx:a]atrim=start=0:end=%.3f,asetpts=PTS-STARTPTS[a$idx]".format(duration)
                    else ->
                        "[$idx:a]asetpts=PTS-STARTPTS[a$idx]"
                }
            }

            val n = keepRanges.size
            val concatV = if (!isAudioOnly) 1 else 0
            val concatA = if (keepAudio) 1 else 0
            // Interleaved inputs required by concat: [v0][a0][v1][a1]...
            val concatInputs = buildString {
                for (i in 0 until n) {
                    if (!isAudioOnly) append("[v$i]")
                    if (keepAudio) append("[a$i]")
                }
            }
            val outLabels = buildString {
                if (!isAudioOnly) append("[outv]")
                if (keepAudio) append("[outa]")
            }
            val concatFilter = "${concatInputs}concat=n=$n:v=$concatV:a=$concatA$outLabels"

            val allFilters = (
                (if (!isAudioOnly) videoFilters else emptyList()) +
                    audioFilters +
                    listOf(concatFilter)
            ).toMutableList()

            // A stream fed by filter_complex cannot also receive -vf. Move any
            // policy-generated downscale operation into the same graph, after
            // concatenation, and map its output label instead.
            val encoderArgs = (
                if (useFallbackProfile) plan.fallbackVideoEncoderArgs
                else plan.videoEncoderArgs
            ).toMutableList()

            var videoOutputLabel = "outv"
            if (!isAudioOnly) {
                val scaleIndex = encoderArgs.indexOfFirst { it == "-vf" }
                if (scaleIndex >= 0 && scaleIndex + 1 < encoderArgs.size) {
                    val videoFilter = encoderArgs[scaleIndex + 1]
                    val scaleHeight = Regex("""scale=-2:(\d+)""")
                        .find(videoFilter)
                        ?.groupValues
                        ?.get(1)

                    if (scaleHeight != null) {
                        encoderArgs.removeAt(scaleIndex + 1)
                        encoderArgs.removeAt(scaleIndex)

                        allFilters += "[outv]scale=-2:$scaleHeight[outv_scaled]"
                        videoOutputLabel = "outv_scaled"
                    }
                }
            }

            validateSinglePassGraph(
                filters = allFilters,
                expectedBranchCount = n,
                slowSeek = slowSeek,
                isAudioOnly = isAudioOnly,
                keepAudio = keepAudio
            )

            args += listOf("-filter_complex", allFilters.joinToString(";"))
            if (!isAudioOnly) args += listOf("-map", "[$videoOutputLabel]")
            if (keepAudio) args += listOf("-map", "[outa]")
            if (!isAudioOnly) {
                args += encoderArgs
            }
            if (keepAudio) {
                args += if (useFallbackProfile) plan.fallbackAudioEncoderArgs else plan.audioEncoderArgs
            }
            plan.outputMuxer?.let { args += listOf("-f", it) }
            args += tempOut.absolutePath
            return args
        }

        var cmdArgs = buildSinglePassArgs(useFallbackProfile = false)
        var cmdString = cmdArgs.joinToString(" ")
        Log.d(TAG, "Single-pass args: $cmdString")
        DiagLog.append("Engine", "Single-pass args: $cmdString")

        val jobStartMs = System.currentTimeMillis()
        onProgressNumeric?.invoke(0, 1)

        // Ticker: elapsed only — no ETA for a single opaque ffmpeg call
        val modeLabel = when {
            plan.videoEncoderArgs.any { it.contains("mediacodec") } -> "HW"
            plan.videoEncoderArgs.any { it.contains("libx264") || it.contains("libx265") || it.contains("libvpx") || it.contains("libsvtav1") || it.contains("libaom") || it.contains("mpeg4") } -> "SW"
            else -> "Fast"
        }
        val tickerActive = java.util.concurrent.atomic.AtomicBoolean(true)
        val tickerThread = Thread {
            while (tickerActive.get()) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                if (!tickerActive.get()) break
                val elapsed = System.currentTimeMillis() - jobStartMs
                onProgress?.invoke("$modeLabel single-pass encoding…\n${formatDuration(elapsed)} elapsed")
            }
        }
        tickerThread.isDaemon = true
        tickerThread.start()

        var session = FFmpegKit.executeWithArguments(cmdArgs.toTypedArray())
        val hasFallbackProfile = false
        if (!ReturnCode.isSuccess(session.returnCode) && !ReturnCode.isCancel(session.returnCode) && hasFallbackProfile) {
            DiagLog.append("Engine", "Single-pass primary profile failed, retrying with fallback profile")
            cmdArgs = buildSinglePassArgs(useFallbackProfile = true).toMutableList()
            cmdString = cmdArgs.joinToString(" ")
            Log.d(TAG, "Single-pass fallback args: $cmdString")
            DiagLog.append("Engine", "Single-pass fallback args: $cmdString")
            session = FFmpegKit.executeWithArguments(cmdArgs.toTypedArray())
        }
        tickerActive.set(false)
        tickerThread.join(200)

        when {
            ReturnCode.isCancel(session.returnCode) -> { tempOut.delete(); error("CANCELLED") }
            !ReturnCode.isSuccess(session.returnCode) -> {
                val rc = session.returnCode?.value ?: -1
        val modeLabel2 = when {
            plan.videoEncoderArgs.any { it.contains("mediacodec") } -> "HW-Accurate"
            plan.videoEncoderArgs.any { it.contains("libx264") || it.contains("libx265") || it.contains("libvpx") || it.contains("libsvtav1") || it.contains("libaom") || it.contains("mpeg4") } -> "SW-Accurate"
            else -> "Fast"
        }

        DiagLog.append("ffkit", "\n--- FFmpeg session failed (rc=$rc) ---")
        session.output?.takeIf { it.isNotBlank() }?.let {
            DiagLog.append("ffkit", it)
        }
        session.allLogs.forEach { log ->
            log.message?.takeIf { it.isNotBlank() }?.let {
                DiagLog.append("ffkit", it)
            }
        }
        session.failStackTrace?.let {
            DiagLog.append("ffkit", "failStackTrace:\n$it")
        }
        DiagLog.append("ffkit", "--- end failed FFmpeg session ---")
        DiagLog.append("Engine", "Single-pass FAILED rc=$rc; see FFmpeg output")
        tempOut.delete()
        error("$modeLabel2 failed (rc=$rc). See FFmpeg output below.")
            }
            else -> {
                onProgressNumeric?.invoke(1, 1)
                tempOut.copyTo(output, overwrite = true)
                tempOut.delete()
            }
        }
    }

    private fun executeSegment(
        input: File,
        output: File,
        startSec: Double,
        durationSec: Double?,
        plan: TranscodePolicy.ProcessingPlan,
        slowSeek: Boolean = plan.useSlowSeek,
        useFallbackVideoArgs: Boolean = false,
        useFallbackAudioArgs: Boolean = false
    ): Int {
        val keepVideo = !plan.dropVideo
        val keepAudio = !plan.dropAudio
        val isCopy = (!keepVideo || plan.canCopyVideo) &&
            (!keepAudio || plan.canCopyAudio)
        val audioOnly = !keepVideo

        val args = mutableListOf("-y")

        if (slowSeek && !isCopy) {
            args += plan.hwDecoderArgs
            args += listOf("-i", input.absolutePath)
            args += listOf("-ss", startSec.toString())
            if (durationSec != null) args += listOf("-t", durationSec.toString())
            args += listOf("-map", "0")
        } else if (slowSeek) {
            args += listOf("-ss", startSec.toString())
            if (durationSec != null) args += listOf("-t", durationSec.toString())
            args += plan.hwDecoderArgs
            args += listOf("-i", input.absolutePath)
            args += listOf("-map", "0")
        } else {
            args += listOf("-ss", startSec.toString())
            if (durationSec != null) args += listOf("-t", durationSec.toString())
            args += plan.hwDecoderArgs
            args += listOf("-i", input.absolutePath)
        }

        if (isCopy) {
            if (!keepVideo) args += "-vn"
            if (!keepAudio) args += "-an"
            if (keepVideo && keepAudio) {
                args += listOf("-c", "copy")
            } else {
                if (keepVideo) args += listOf("-c:v", "copy")
                if (keepAudio) args += listOf("-c:a", "copy")
            }
            args += listOf("-avoid_negative_ts", "make_zero")
        } else {
            if (!keepVideo) {
                args += "-vn"
            } else if (plan.canCopyVideo) {
                args += listOf("-c:v", "copy")
            } else {
                args += if (useFallbackVideoArgs) plan.fallbackVideoEncoderArgs else plan.videoEncoderArgs
            }

            if (!keepAudio) {
                args += "-an"
            } else if (plan.canCopyAudio) {
                args += listOf("-c:a", "copy")
            } else {
                args += if (useFallbackAudioArgs) plan.fallbackAudioEncoderArgs else plan.audioEncoderArgs
            }

            args += listOf("-avoid_negative_ts", "make_zero")
        }

        plan.outputMuxer?.let { args += listOf("-f", it) }
        args += output.absolutePath

        val cmdString = args.joinToString(" ")
        Log.d(TAG, "FFmpeg args: $cmdString")
        DiagLog.append("FFmpeg", "args: $cmdString")
        val session = FFmpegKit.execute(cmdString)
        val rcInt = session.returnCode?.value ?: -1
        Log.d(TAG, "FFmpeg rc=$rcInt exists=${output.exists()} size=${output.length()}")
        DiagLog.append("FFmpeg", "rc=$rcInt exists=${output.exists()} size=${output.length()}")
        if (!ReturnCode.isSuccess(session.returnCode) && !ReturnCode.isCancel(session.returnCode)) {
            Log.e(TAG, "FFmpeg FAILED rc=$rcInt — args were: $cmdString")
            DiagLog.append("FFmpeg", "FAILED rc=$rcInt")
        }
        return if (ReturnCode.isCancel(session.returnCode)) RETURN_CANCEL
               else if (ReturnCode.isSuccess(session.returnCode)) RETURN_SUCCESS
               else rcInt
    }
}
