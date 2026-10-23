// app/src/main/java/com/sponsorcut/FFProbeInspector.kt

package com.sponsorcut

import android.util.Log
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single source of truth for media file characteristics.
 * Primary: FFprobeKit.getMediaInformation() structured Java API.
 * Fallback: raw ffprobe -of json execution + JSON parse.
 * Nothing else in the app calls FFprobe directly.
 */
object FFProbeInspector {

    private const val TAG = "FFProbeInspector"

    data class VideoInfo(
        val videoCodec: String,
        val width: Int?,
        val height: Int?,
        val fps: Float?,
        val pixFmt: String?,
        val videoBitrate: Long?,
        val videoIndex: Int?,
        val audioCodec: String?,
        val sampleRate: Int?,
        val audioBitrate: Long?,
        val audioChannels: Int?,
        val audioIndex: Int?,
        val durationSec: Double?,
        val containerBitrate: Long?,
        val containerFormat: String?,
        val isTsEncapsulated: Boolean = false
    ) {
        val pixels: Int? get() = if (width != null && height != null) width * height else null

        val summaryLine: String get() = buildString {
            append(videoCodec.uppercase())
            if (width != null && height != null) append(" ${width}x${height}")
            if (fps != null) append(" @${"%.2f".format(fps)}fps")
            val vbr = videoBitrate ?: containerBitrate
            if (vbr != null) append(" ${"%.0f".format(vbr / 1000.0)}kbps")
            if (audioCodec != null) {
                append(" | ${audioCodec.uppercase()}")
                if (sampleRate != null) append(" ${sampleRate}Hz")
                if (audioBitrate != null) append(" ${"%.0f".format(audioBitrate / 1000.0)}kbps")
            }
            if (!containerFormat.isNullOrBlank()) append(" | ${containerFormat.uppercase()}")
        }
    }

    fun inspect(file: File): VideoInfo? {
        // Primary: structured API — parses ffprobe JSON internally
        val fromApi = inspectViaApi(file)
        // JSON path provides additional fields (container format, TS hint). Merge both.
        if (fromApi == null || fromApi.videoCodec == "unknown") {
            Log.w(TAG, "API inspection uncertain (${fromApi?.videoCodec}), trying JSON fallback")
        }
        val fromJson = inspectViaJson(file)

        val merged = merge(fromApi, fromJson)
        if (merged != null && merged.videoCodec != "unknown") {
            val source = when {
                fromApi != null && fromJson != null -> "API+JSON"
                fromApi != null -> "API"
                else -> "JSON"
            }
            Log.i(TAG, "Inspected via $source: ${merged.summaryLine}")
            DiagLog.append("FFProbe", "$source ok: ${merged.summaryLine}")
            return merged
        }

        val best = merged
        if (best != null) {
            Log.w(TAG, "Both methods uncertain, returning best result: ${best.summaryLine}")
            DiagLog.append("FFProbe", "uncertain result: ${best.summaryLine}")
            return best
        }

        Log.w(TAG, "All inspection methods failed — returning minimal VideoInfo")
        DiagLog.append("FFProbe", "FAILED — returning minimal stub")
        return VideoInfo(
            videoCodec = "unknown",
            width = null, height = null, fps = null, pixFmt = null,
            videoBitrate = null, videoIndex = null,
            audioCodec = "unknown",
            sampleRate = null, audioBitrate = null, audioChannels = null, audioIndex = null,
            durationSec = null, containerBitrate = null, containerFormat = null
        )
    }

    private fun merge(primary: VideoInfo?, secondary: VideoInfo?): VideoInfo? {
        if (primary == null) return secondary
        if (secondary == null) return primary

        // The structured API can report embedded album art (mjpeg/png/etc.) as
        // a video stream. The JSON path explicitly excludes attached pictures,
        // so use it to reject cover art when it found no real video stream.
        val imageLikeCodecs = setOf("mjpeg", "png", "bmp", "gif", "webp", "tiff")
        val primaryVideoCodec =
            if (
                primary.videoCodec.lowercase() in imageLikeCodecs &&
                secondary.videoCodec == "unknown"
            ) {
                "unknown"
            } else {
                primary.videoCodec
            }

        val videoBitrateSource =
            if (secondary.videoBitrate != null) "JSON"
            else if (primary.videoBitrate != null) "API"
            else "none"

        val audioBitrateSource =
            if (secondary.audioBitrate != null) "JSON"
            else if (primary.audioBitrate != null) "API"
            else "none"

        Log.i(
            TAG,
            "FFProbe bitrate inference: " +
                "video=$videoBitrateSource(${secondary.videoBitrate ?: primary.videoBitrate ?: "null"}), " +
                "audio=$audioBitrateSource(${secondary.audioBitrate ?: primary.audioBitrate ?: "null"})"
        )

        return VideoInfo(
            videoCodec = if (primaryVideoCodec != "unknown") primaryVideoCodec else secondary.videoCodec,
            width = primary.width ?: secondary.width,
            height = primary.height ?: secondary.height,
            fps = primary.fps ?: secondary.fps,
            pixFmt = primary.pixFmt ?: secondary.pixFmt,
            videoBitrate = secondary.videoBitrate ?: primary.videoBitrate,
            videoIndex = primary.videoIndex ?: secondary.videoIndex,
            audioCodec = primary.audioCodec ?: secondary.audioCodec,
            sampleRate = primary.sampleRate ?: secondary.sampleRate,
            audioBitrate = secondary.audioBitrate ?: primary.audioBitrate,
            audioChannels = primary.audioChannels ?: secondary.audioChannels,
            audioIndex = primary.audioIndex ?: secondary.audioIndex,
            durationSec = primary.durationSec ?: secondary.durationSec,
            containerBitrate = primary.containerBitrate ?: secondary.containerBitrate,
            containerFormat = primary.containerFormat ?: secondary.containerFormat,
            isTsEncapsulated = primary.isTsEncapsulated || secondary.isTsEncapsulated
        )
    }

    private fun inspectViaApi(file: File): VideoInfo? {
        return try {
            val session = FFprobeKit.getMediaInformation(file.absolutePath)
            val info = session?.mediaInformation ?: run {
                Log.w(TAG, "inspectViaApi: null mediaInformation, rc=${session?.returnCode}")
                DiagLog.append("FFProbe", "API mediaInformation null rc=${session?.returnCode}")
                return null
            }

            var videoCodec = "unknown"
            var width: Int? = null; var height: Int? = null; var fps: Float? = null
            var pixFmt: String? = null; var videoBitrate: Long? = null; var videoIndex: Int? = null
            var audioCodec: String? = null; var sampleRate: Int? = null
            var audioBitrate: Long? = null; var audioChannels: Int? = null; var audioIndex: Int? = null

            info.streams?.forEach { stream ->
                when (stream.type?.lowercase()) {
                    "video" -> if (videoIndex == null) {
                        videoCodec = stream.codec?.lowercase() ?: "unknown"
                        width = stream.width?.toInt()
                        height = stream.height?.toInt()
                        fps = parseFraction(stream.averageFrameRate)
                        videoBitrate = stream.bitrate?.toLongOrNull()
                        videoIndex = 0
                        // isTsEncapsulated not available via this API; defaults false
                    }
                    "audio" -> if (audioIndex == null) {
                        audioCodec = stream.codec?.lowercase()
                        sampleRate = stream.sampleRate?.toIntOrNull()
                        audioBitrate = stream.bitrate?.toLongOrNull()
                        audioIndex = 0
                    }
                }
            }

            val durationSec = info.duration?.toDoubleOrNull()
            val containerBitrate = info.bitrate?.toLongOrNull()
            VideoInfo(
                videoCodec, width, height, fps, pixFmt,
                videoBitrate, videoIndex,
                audioCodec, sampleRate, audioBitrate, audioChannels, audioIndex,
                durationSec, containerBitrate, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "inspectViaApi failed: ${e.message}")
            DiagLog.append("FFProbe", "inspectViaApi exception: ${e.message}")
            null
        }
    }

    private fun inspectViaJson(file: File): VideoInfo? {
        return try {
            val session = FFprobeKit.execute(
                "-v quiet -print_format json -show_streams -show_format -show_entries stream=index,codec_type,codec_name,width,height,r_frame_rate,bit_rate,sample_rate,channels,channel_layout,duration:format=bit_rate,duration,format_name ${file.absolutePath}"
            )

            if (!ReturnCode.isSuccess(session.returnCode)) {
                Log.w(TAG, "ffprobe JSON execute rc=${session.returnCode}")
                DiagLog.append("FFProbe", "JSON execute failed rc=${session.returnCode}")
                return null
            }

            val raw = session.output?.trim() ?: ""
            Log.d(TAG, "ffprobe JSON output length=${raw.length}")
            DiagLog.append("FFProbe", "JSON output length=${raw.length}")

            val jsonStart = raw.indexOf('{')
            val jsonEnd = raw.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd < 0) {
                Log.w(TAG, "No JSON in ffprobe output: ${raw.take(300)}")
                DiagLog.append("FFProbe", "No JSON found, raw=${raw.take(200)}")
                return null
            }

            parseJson(raw.substring(jsonStart, jsonEnd + 1))
        } catch (e: Exception) {
            Log.e(TAG, "inspectViaJson failed: ${e.message}")
            DiagLog.append("FFProbe", "inspectViaJson exception: ${e.message}")
            null
        }
    }

    private fun parseJson(json: String): VideoInfo? {
        val root = JSONObject(json)

        var videoCodec = "unknown"
        var width: Int? = null
        var height: Int? = null
        var fps: Float? = null
        var pixFmt: String? = null
        var videoBitrate: Long? = null
        var videoIndex: Int? = null
        var audioCodec: String? = null
        var sampleRate: Int? = null
        var audioBitrate: Long? = null
        var audioChannels: Int? = null
        var audioIndex: Int? = null
        var isTsEncapsulated = false

        val streams = root.optJSONArray("streams") ?: JSONArray()
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            val attachedPic = s.optJSONObject("disposition")
                ?.optInt("attached_pic", 0) == 1

            when (s.optString("codec_type")) {
                "video" -> {
                    if (attachedPic || videoIndex != null) {
                        continue
                    }

                    videoCodec = s.optString("codec_name", "unknown").lowercase()
                    width = s.optInt("width").takeIf { it > 0 }
                    height = s.optInt("height").takeIf { it > 0 }
                    fps = parseFraction(s.optString("r_frame_rate"))
                        ?: parseFraction(s.optString("avg_frame_rate"))
                    pixFmt = s.optString("pix_fmt").ifBlank { null }
                    videoBitrate = s.optString("bit_rate").toLongOrNull()
                    videoIndex = s.optInt("index")
                    // H.264 Annex B detection. Annex B is common in MPEG-TS and
                    // streamed inputs; it can require slower, more conservative
                    // seeking. Future: add an equivalent HEVC Annex B detection
                    // and handling path; do not treat this H.264-only field as
                    // a generic TS/HEVC indicator.
                    isTsEncapsulated = s.optString("is_avc") == "false"
                }
                "audio" -> {
                    if (audioIndex != null) {
                        continue
                    }

                    audioCodec = s.optString("codec_name").lowercase().ifBlank { null }
                    sampleRate = s.optString("sample_rate").toIntOrNull()
                    audioBitrate = s.optString("bit_rate").toLongOrNull()
                    audioChannels = s.optInt("channels").takeIf { it > 0 }
                    audioIndex = s.optInt("index")
                }
            }
        }

        DiagLog.append(
            "FFProbe",
            "JSON selected video: index=$videoIndex codec=$videoCodec bitrate=$videoBitrate"
        )
        DiagLog.append(
            "FFProbe",
            "JSON selected audio: index=$audioIndex codec=$audioCodec bitrate=$audioBitrate " +
                "sampleRate=$sampleRate channels=$audioChannels"
        )
        val fmt = root.optJSONObject("format")
        val durationSec = fmt?.optString("duration")?.toDoubleOrNull()
        val containerBitrate = fmt?.optString("bit_rate")?.toLongOrNull()
        val containerFormat = fmt?.optString("format_name")?.lowercase()?.ifBlank { null }

        // MPEG-TS is the authoritative signal for TS-style seeking. The older
        // is_avc check alone missed real MPEG-TS inputs whose JSON did not
        // expose that field as expected.
        if (containerFormat?.contains("mpegts") == true) {
            isTsEncapsulated = true
        }

        return VideoInfo(
            videoCodec, width, height, fps, pixFmt, videoBitrate, videoIndex,
            audioCodec, sampleRate, audioBitrate, audioChannels, audioIndex,
            durationSec, containerBitrate, containerFormat, isTsEncapsulated
        )
    }

    private fun parseFraction(raw: String?): Float? {
        if (raw.isNullOrBlank() || raw == "0/0") return null
        return try {
            if ('/' in raw) {
                val (n, d) = raw.trim().split('/')
                val den = d.toFloat()
                if (den == 0f) null else n.toFloat() / den
            } else raw.trim().toFloat()
        } catch (e: Exception) { null }
    }
}
