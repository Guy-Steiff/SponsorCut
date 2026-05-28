package com.sponsorcut

import com.arthenica.mobileffmpeg.FFmpeg
import com.arthenica.mobileffmpeg.FFprobe
import com.arthenica.mobileffmpeg.Config
import android.util.Log
import java.io.File

object FfmpegEngine {

    private const val TAG = "FfmpegEngine"

    data class StreamCodecs(
        val videoCodec: String,
        val audioCodec: String?,
        val width: Long?,
        val height: Long?,
        val fps: String?,
        val videoBitrate: String?,
        val audioBitrate: String?,
        val sampleRate: String?
    )

    fun probeCodecs(input: File): StreamCodecs {
        val info = FFprobe.getMediaInformation(input.absolutePath)
        var videoCodec = "unknown"
        var audioCodec: String? = null
        var width: Long? = null
        var height: Long? = null
        var fps: String? = null
        var videoBitrate: String? = null
        var audioBitrate: String? = null
        var sampleRate: String? = null

        info?.streams?.forEach { stream ->
            when (stream.type?.lowercase()) {
                "video" -> {
                    videoCodec = stream.codec ?: "unknown"
                    width = stream.width
                    height = stream.height
                    fps = stream.averageFrameRate
                    videoBitrate = stream.bitrate
                }
                "audio" -> {
                    audioCodec = stream.codec
                    audioBitrate = stream.bitrate
                    sampleRate = stream.sampleRate
                }
            }
        }
        return StreamCodecs(videoCodec, audioCodec, width, height, fps, videoBitrate, audioBitrate, sampleRate)
    }

    fun process(
        input: File,
        output: File,
        segments: List<Pair<Double, Double>>,
        cacheDir: File,
        frameAccurate: Boolean = false,
        onProgress: ((step: String) -> Unit)? = null
    ) {
        val phases = arrayOf(".", "..", "...")
        var phaseIdx = 0

        fun updatePhase(message: String) {
            onProgress?.invoke("$message ${phases[phaseIdx]}")
            phaseIdx = (phaseIdx + 1) % phases.size
        }

        if (segments.isEmpty()) return

        // Build keep-ranges (gaps between sponsor segments)
        val sorted = segments.sortedBy { it.first }
        val keepRanges = mutableListOf<Pair<Double, Double>>()
        var lastEnd = 0.0
        for ((start, end) in sorted) {
            if (start > lastEnd + 0.01) keepRanges += lastEnd to start
            if (end > lastEnd) lastEnd = end
        }
        keepRanges += lastEnd to Double.MAX_VALUE   // tail

        Log.d(TAG, "Keep ranges (${keepRanges.size}): $keepRanges")

        val parts = mutableListOf<File>()
        val total = keepRanges.size

        try {
            for ((idx, range) in keepRanges.withIndex()) {
                val (keepStart, keepEnd) = range
                val duration = if (keepEnd == Double.MAX_VALUE) null else keepEnd - keepStart

                if (duration != null && duration <= 0.01) {
                    Log.d(TAG, "Skipping zero-length range $keepStart->$keepEnd")
                    continue
                }

                val partFile = File(cacheDir, "part_${idx}_${System.currentTimeMillis()}.mp4")
//                onProgress?.invoke("Part ${idx + 1}/$total: %.1fs – %s".format(
//                    keepStart,
//                    if (duration == null) "end" else "%.1fs".format(keepEnd)
//                ))
                updatePhase("Processing part ${idx + 1}/$total")

                val rc: Int
                if (frameAccurate) {
                    // Frame-accurate: decode frames, trim precisely, re-encode (slower but exact cuts)
                    val vFilter = if (duration != null)
                        "trim=start=0:end=$duration,setpts=PTS-STARTPTS,format=yuv420p"
                    else
                        "setpts=PTS-STARTPTS,format=yuv420p"

                    val cmd = buildString {
                        append("-y -ss $keepStart ")
                        if (duration != null) append("-t $duration ")
                        append("-i \"${input.absolutePath}\" ")
                        append("-filter:v \"$vFilter\" ")
                        append("-c:v libx264 -preset veryfast -crf 23 ")
                        append("-c:a aac -b:a 128k ")
                        append("\"${partFile.absolutePath}\"")
                    }
                    Log.d(TAG, "Frame-accurate trim: $cmd")
                    rc = FFmpeg.execute(cmd)
                } else {
                    // Fast stream copy — no re-encode, keyframe-boundary cuts
                    val cmd = buildString {
                        append("-y -ss $keepStart ")
                        if (duration != null) append("-t $duration ")
                        append("-i \"${input.absolutePath}\" ")
                        append("-c copy ")
                        append("-avoid_negative_ts make_zero ")
                        append("\"${partFile.absolutePath}\"")
                    }
                    Log.d(TAG, "Fast copy trim: $cmd")
                    rc = FFmpeg.execute(cmd)
                }

                if (rc != Config.RETURN_CODE_SUCCESS) error("FFmpeg trim part $idx failed (rc=$rc)")

                if (partFile.exists() && partFile.length() > 0) {
                    parts += partFile
                    onProgress?.invoke("Part ${idx + 1}/$total done ✓")
                } else {
                    Log.w(TAG, "Part $idx empty, skipping")
                }
            }

            if (parts.isEmpty()) error("No output parts produced")

            if (parts.size == 1) {
                onProgress?.invoke("Finalising...")
                parts[0].copyTo(output, overwrite = true)
                return
            }

            onProgress?.invoke("Merging ${parts.size} parts...")
            val concatFile = File(cacheDir, "concat_${System.currentTimeMillis()}.txt")
            concatFile.writeText(parts.joinToString("\n") { "file '${it.absolutePath}'" })

            val concatCmd = "-y -f concat -safe 0 -i \"${concatFile.absolutePath}\" -c copy \"${output.absolutePath}\""
            Log.d(TAG, "Concat: $concatCmd")
            val rc = FFmpeg.execute(concatCmd)
            concatFile.delete()
            if (rc != Config.RETURN_CODE_SUCCESS) error("FFmpeg concat failed (rc=$rc)")

        } finally {
            parts.forEach { it.delete() }
        }
    }
}
