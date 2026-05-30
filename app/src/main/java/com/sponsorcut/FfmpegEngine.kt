package com.sponsorcut

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
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

        var regN  = 0
        var regSX = 0.0
        var regSY = 0.0
        var regSXY= 0.0
        var regSX2= 0.0

        val isCopyAll = plan.canCopyVideo && plan.canCopyAudio
        val ext = if (isCopyAll) input.extension.ifBlank { "mp4" } else "mp4"

        fun formatDuration(ms: Long): String {
            val s = ms / 1000
            return when {
                s < 60   -> "${s}s"
                s < 3600 -> "%dm%02ds".format(s / 60, s % 60)
                else     -> "%dh%02dm".format(s / 3600, (s % 3600) / 60)
            }
        }

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
                val label = "Part ${idx + 1}/$total: %.1fs → %s\n$stats".format(
                    keepStart,
                    if (keepEnd == Double.MAX_VALUE) "end" else "%.1fs".format(keepEnd)
                )
                onProgressNumeric?.invoke(idx, total)
                onProgress?.invoke(label)

                val segStartMs = System.currentTimeMillis()

                var rc = executeSegment(input, partFile, keepStart, duration, plan, slowSeek = false)

                if (rc == RETURN_CANCEL) error("CANCELLED")

                if (rc != RETURN_SUCCESS && !plan.canCopyVideo) {
                    DiagLog.append("Engine", "Part $idx fast-seek rc=1, retrying with slow seek")
                    onProgress?.invoke("Part ${idx + 1}/$total: retrying with slow seek…")
                    partFile.delete()
                    rc = executeSegment(input, partFile, keepStart, duration, plan, slowSeek = true)
                    if (rc == RETURN_CANCEL) error("CANCELLED")
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
                return
            }

            onProgress?.invoke("Merging ${parts.size} parts...")
            val concatFile = File(cacheDir, "concat_${System.currentTimeMillis()}.txt")
            concatFile.writeText(parts.joinToString("\n") { "file '${it.absolutePath}'" })
            val concatOut = File(cacheDir, "concat_out_${System.currentTimeMillis()}.$ext")
            val concatArgs = "-y -f concat -safe 0 -i ${concatFile.absolutePath} -c copy ${concatOut.absolutePath}"
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
    }

    private const val RETURN_SUCCESS = 0
    private const val RETURN_CANCEL = 255

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
            args += listOf("-i", input.absolutePath)
            args += listOf("-ss", startSec.toString())
            if (durationSec != null) args += listOf("-t", durationSec.toString())
            args += listOf("-map", "0")
        } else if (slowSeek) {
            args += listOf("-ss", startSec.toString())
            if (durationSec != null) args += listOf("-t", durationSec.toString())
            args += listOf("-i", input.absolutePath)
            args += listOf("-map", "0")
        } else {
            args += listOf("-ss", startSec.toString())
            if (durationSec != null) args += listOf("-t", durationSec.toString())
            args += listOf("-i", input.absolutePath)
        }

        if (isCopy) {
            if (audioOnly) {
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
            if (audioOnly) args += listOf("-vn")
            args += listOf("-avoid_negative_ts", "make_zero")
        }

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
