package com.sponsorcut

import com.arthenica.mobileffmpeg.FFmpeg
import com.arthenica.mobileffmpeg.Config
import android.util.Log
import java.io.File

/**
 * Execution layer only.
 * Receives a ProcessingPlan from TranscodePolicy and carries it out.
 * Never calls ffprobe directly — use FFProbeInspector for that.
 */
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
    ) {
        if (segments.isEmpty()) return

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

        val parts = mutableListOf<File>()
        val total = keepRanges.size
        val jobStartMs = System.currentTimeMillis()
        var completedCount = 0

        // Preserve source container for stream-copy; use mp4 for re-encode
        // (WebM/MKV don't support H264/AAC so re-encode must target mp4)
        val isCopyAll = plan.canCopyVideo && plan.canCopyAudio
        val ext = if (isCopyAll) input.extension.ifBlank { "mp4" } else "mp4"

        fun formatDuration(ms: Long): String {
            val s = ms / 1000
            return if (s < 60) "${s}s" else "%dm%02ds".format(s / 60, s % 60)
        }

        try {
            for ((idx, range) in keepRanges.withIndex()) {
                val (keepStart, keepEnd) = range
                // For the last segment (keepEnd=MAX_VALUE), compute explicit duration from
                // known file duration so mobile-ffmpeg doesn't have to seek to EOF to find it.
                // This fixes rc=1 on TS-wrapped files where duration isn't in the container index.
                val duration: Double? = when {
                    keepEnd != Double.MAX_VALUE -> keepEnd - keepStart
                    fileDurationSec != null -> (fileDurationSec - keepStart).coerceAtLeast(0.1)
                    else -> null  // no duration known — let ffmpeg run to EOF
                }

                if (duration != null && duration <= 0.01) {
                    Log.d(TAG, "Skipping zero-length range $keepStart->$keepEnd")
                    continue
                }

                val partFile = File(cacheDir, "part_${idx}_${System.currentTimeMillis()}.$ext")

                val elapsed = System.currentTimeMillis() - jobStartMs
                val rate = if (elapsed > 0 && completedCount > 0) completedCount / (elapsed / 1000.0) else 0.0
                val etaMs = if (rate > 0) (((total - completedCount) / rate) * 1000).toLong() else null
                val stats = if (completedCount > 0) buildString {
                    append("${completedCount}/$total done [${formatDuration(elapsed)} elapsed")
                    if (etaMs != null) append(", ~${formatDuration(etaMs)} left")
                    append("]")
                } else "${formatDuration(elapsed)} elapsed"
                val label = "Part ${idx + 1}/$total: %.1fs → %s\n$stats".format(
                    keepStart,
                    if (keepEnd == Double.MAX_VALUE) "end" else "%.1fs".format(keepEnd)
                )
                onProgressNumeric?.invoke(idx, total)
                onProgress?.invoke(label)

                var rc = executeSegment(input, partFile, keepStart, duration, plan, slowSeek = false)

                if (rc == Config.RETURN_CODE_CANCEL) error("CANCELLED")

                if (rc != Config.RETURN_CODE_SUCCESS && !plan.canCopyVideo) {
                    // Fast seek failed for re-encode (common with TS-wrapped files where seek
                    // index is missing/corrupt). Retry with slow seek (decode from start).
                    DiagLog.append("Engine", "Part $idx fast-seek rc=1, retrying with slow seek")
                    onProgress?.invoke("Part ${idx + 1}/$total: retrying with slow seek…")
                    partFile.delete()
                    rc = executeSegment(input, partFile, keepStart, duration, plan, slowSeek = true)
                    if (rc == Config.RETURN_CODE_CANCEL) error("CANCELLED")
                }

                if (rc != Config.RETURN_CODE_SUCCESS) error("FFmpeg trim part $idx failed (rc=$rc)")

                if (partFile.exists() && partFile.length() > 0) {
                    parts += partFile
                    completedCount++
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
                return
            }

            onProgress?.invoke("Merging ${parts.size} parts...")
            val concatFile = File(cacheDir, "concat_${System.currentTimeMillis()}.txt")
            concatFile.writeText(parts.joinToString("\n") { "file '${it.absolutePath}'" })
            val concatOut = File(cacheDir, "concat_out_${System.currentTimeMillis()}.$ext")
            val concatArgs = arrayOf("-y", "-f", "concat", "-safe", "0",
                "-i", concatFile.absolutePath, "-c", "copy", concatOut.absolutePath)
            Log.d(TAG, "Concat args: ${concatArgs.joinToString(" ")}")
            val rc = FFmpeg.execute(concatArgs)
            concatFile.delete()
            if (rc == Config.RETURN_CODE_CANCEL) error("CANCELLED")
            if (rc != Config.RETURN_CODE_SUCCESS) error("FFmpeg concat failed (rc=$rc)")
            concatOut.copyTo(output, overwrite = true)
            concatOut.delete()
            onProgressNumeric?.invoke(total, total)

        } finally {
            parts.forEach { it.delete() }
        }
    }

    private fun executeSegment(
        input: File,
        output: File,
        startSec: Double,
        durationSec: Double?,
        plan: TranscodePolicy.ProcessingPlan,
        slowSeek: Boolean = plan.useSlowSeek
    ): Int {
        val isCopy = plan.canCopyVideo && plan.canCopyAudio
        val audioOnly = plan.rationale.contains("audio-only")

        val args = mutableListOf("-y")

        if (slowSeek && !isCopy) {
            // Slow (accurate) seek: -ss after -i, decodes from start to exact frame.
            // Used for TS-wrapped files and as fallback when fast seek fails.
            args += listOf("-i", input.absolutePath)
            args += listOf("-ss", startSec.toString())
            if (durationSec != null) args += listOf("-t", durationSec.toString())
            args += listOf("-map", "0")
        } else if (slowSeek) {
            // TS stream-copy with fast seek + -map 0 for stream selection.
            args += listOf("-ss", startSec.toString())
            if (durationSec != null) args += listOf("-t", durationSec.toString())
            args += listOf("-i", input.absolutePath)
            args += listOf("-map", "0")
        } else {
            // Normal fast seek
            args += listOf("-ss", startSec.toString())
            if (durationSec != null) args += listOf("-t", durationSec.toString())
            args += listOf("-i", input.absolutePath)
        }

        if (isCopy) {
            if (audioOnly) {
                // Audio-only: explicitly exclude video, map only audio stream
                args += listOf("-vn", "-c:a", "copy", "-avoid_negative_ts", "make_zero")
            } else {
                args += listOf("-c", "copy", "-avoid_negative_ts", "make_zero")
            }
        } else {
            if (plan.canCopyVideo) {
                args += listOf("-c:v", "copy")
            } else {
                val preset = when {
                    plan.useSlowSeek -> "ultrafast"
                    plan.complexity == TranscodePolicy.ComplexityTier.HIGH -> "faster"
                    else -> "veryfast"
                }
                args += listOf("-c:v", plan.videoEncoder, "-preset", preset, "-crf", "23")
                if (plan.pixFmt != null) args += listOf("-pix_fmt", plan.pixFmt)
            }
            if (plan.canCopyAudio) {
                args += listOf("-c:a", "copy")
            } else {
                args += listOf("-c:a", plan.audioEncoder, "-b:a", "128k")
            }
            if (audioOnly) args += listOf("-vn")  // suppress any spurious video track
            args += listOf("-avoid_negative_ts", "make_zero")
        }

        args += output.absolutePath

        Log.d(TAG, "FFmpeg args: ${args.joinToString(" ")}")
        DiagLog.append("FFmpeg", "args: ${args.joinToString(" ")}")
        val rc = FFmpeg.execute(args.toTypedArray())
        Log.d(TAG, "FFmpeg rc=$rc exists=${output.exists()} size=${output.length()}")
        DiagLog.append("FFmpeg", "rc=$rc exists=${output.exists()} size=${output.length()}")
        if (rc != Config.RETURN_CODE_SUCCESS && rc != Config.RETURN_CODE_CANCEL) {
            Log.e(TAG, "FFmpeg FAILED rc=$rc — args were: ${args.joinToString(" ")}")
            DiagLog.append("FFmpeg", "FAILED rc=$rc")
        }
        return rc
    }
}
