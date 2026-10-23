package com.sponsorcut

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.sponsorcut.core.model.ExecutionMode

object EncoderCatalog {

    private const val TAG = "EncoderCatalog"

    private val RELEVANT = listOf(
        EncoderOption("libx264", "H.264 / AVC", "H.264", "video", false, false),
        EncoderOption("libx265", "H.265 / HEVC", "H.265/HEVC", "video", false, false),
        EncoderOption("libvpx-vp9", "VP9", "VP9", "video", false, false),
        EncoderOption("libsvtav1", "AV1 (SVT)", "AV1", "video", false, false),
        EncoderOption("libaom-av1", "AV1 (AOM)", "AV1", "video", false, false),
        EncoderOption("mpeg4", "MPEG-4 Part 2", "MPEG-4 Part 2", "video", false, false),
        EncoderOption("h264_mediacodec", "H.264 MediaCodec", "H.264", "video", true, false),
        EncoderOption("hevc_mediacodec", "HEVC MediaCodec", "H.265/HEVC", "video", true, false),
        EncoderOption("aac", "AAC", "AAC", "audio", false, false),
        EncoderOption("opus", "Opus", "Opus", "audio", false, false),
        EncoderOption("libmp3lame", "MP3", "MP3", "audio", false, false),
        EncoderOption("vorbis", "Vorbis", "Vorbis", "audio", false, false),
        EncoderOption("flac", "FLAC", "FLAC", "audio", false, false)
    )

    private val FAMILY_BY_INPUT_CODEC = mapOf(
        "h264" to "H.264",
        "avc" to "H.264",
        "hevc" to "H.265/HEVC",
        "h265" to "H.265/HEVC",
        "vp9" to "VP9",
        "av1" to "AV1",
        "mpeg4" to "MPEG-4 Part 2",
        "aac" to "AAC",
        "opus" to "Opus",
        "mp3" to "MP3",
        "vorbis" to "Vorbis",
        "flac" to "FLAC"
    )

    fun familyForInputCodec(codec: String?): String? =
        codec?.lowercase()?.let { FAMILY_BY_INPUT_CODEC[it] }

    fun discover(): List<EncoderOption> {
        val availableNames = mutableSetOf<String>()

        try {
            val session = FFmpegKit.executeWithArguments(
                arrayOf("-hide_banner", "-encoders")
            )

            val output = buildString {
                append(session.output ?: "")
                append("\n")
                append(session.allLogsAsString)
            }

            DiagLog.append(
                "EncoderCatalog",
                "encoder output length=${output.length}; return=${session.returnCode}"
            )
            DiagLog.append("ffkit", "--- encoder discovery ---")
            DiagLog.append("ffkit", output.takeLast(12000))
            DiagLog.append("ffkit", "--- end encoder discovery ---")

            for (option in RELEVANT) {
                val pattern = Regex("\\b${Regex.escape(option.name)}\\b")
                if (pattern.containsMatchIn(output)) {
                    availableNames += option.name
                }
            }

            if (!ReturnCode.isSuccess(session.returnCode)) {
                Log.w(TAG, "ffmpeg -encoders returned ${session.returnCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoder discovery failed", e)
            DiagLog.append("EncoderCatalog", "discovery failed: ${e.message}")
        }

        DiagLog.append("EncoderCatalog", "available=${availableNames.sorted()}")
        return RELEVANT.map {
            it.copy(available = it.name in availableNames)
        }
    }

    fun videoOptions(catalog: List<EncoderOption>, mode: ExecutionMode): List<EncoderOption> {
        val preferredFamilies = listOf(
            "H.264",
            "H.265/HEVC",
            "VP9",
            "AV1",
            "MPEG-4 Part 2"
        )

        val relevant = catalog.filter { it.isVideo }
        val ordered = mutableListOf<EncoderOption>()

        for (family in preferredFamilies) {
            ordered += relevant.filter { it.codecFamily == family }
        }
        ordered += relevant.filter { it.codecFamily !in preferredFamilies }

        return when (mode) {
            ExecutionMode.FAST -> ordered.filter { !it.hardware }
            ExecutionMode.SW_ACCURATE -> ordered.filter { !it.hardware }
            ExecutionMode.HW_ACCURATE -> ordered.filter { it.hardware }
            else -> ordered
        }
    }

    fun audioOptions(catalog: List<EncoderOption>): List<EncoderOption> =
        catalog.filter { it.isAudio }

    fun find(catalog: List<EncoderOption>, name: String?): EncoderOption? =
        catalog.firstOrNull { it.name == name }

    fun firstAvailable(
        catalog: List<EncoderOption>,
        family: String,
        mode: ExecutionMode
    ): EncoderOption? =
        videoOptions(catalog, mode).firstOrNull {
            it.available && it.codecFamily == family
        }
}
