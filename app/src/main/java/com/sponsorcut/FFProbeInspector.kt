package com.sponsorcut

import android.util.Log
import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.FFprobe
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single source of truth for media file characteristics.
 * Primary method: runs `ffprobe -of json` via FFprobe.execute() and parses raw JSON.
 * Fallback: uses FFprobe.getMediaInformation() Java API if execute() fails.
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
        /** True when H.264 is in MPEG-TS Annex B format (is_avc=false), e.g. Rumble downloads.
         *  These files need slow seeking and explicit stream mapping to avoid concat drift. */
        val isTsEncapsulated: Boolean = false
    ) {
        val pixels: Int? get() = if (width != null && height != null) width * height else null

        val summaryLine: String get() = buildString {
            append(videoCodec.uppercase())
            if (width != null && height != null) append(" ${width}×${height}")
            if (fps != null) append(" @${"%.2f".format(fps)}fps")
            val vbr = videoBitrate ?: containerBitrate
            if (vbr != null) append(" ${"%.0f".format(vbr / 1000.0)}kbps")
            if (audioCodec != null) {
                append(" | ${audioCodec.uppercase()}")
                if (sampleRate != null) append(" ${sampleRate}Hz")
                if (audioBitrate != null) append(" ${"%.0f".format(audioBitrate / 1000.0)}kbps")
            }
        }
    }

    fun inspect(file: File): VideoInfo? {
        // Primary: invoke ffprobe binary directly with JSON output
        val fromJson = inspectViaJson(file)
        if (fromJson != null && fromJson.videoCodec != "unknown") {
            Log.i(TAG, "Inspected via JSON: $fromJson")
            return fromJson
        }

        // Fallback: Java API
        Log.w(TAG, "JSON inspection returned unknown codec, trying Java API fallback")
        val fromApi = inspectViaApi(file)
        if (fromApi != null) {
            Log.i(TAG, "Inspected via API fallback: $fromApi")
            return fromApi
        }

        // Return whatever the JSON gave us even if codec is unknown
        if (fromJson != null) {
            Log.w(TAG, "Both methods uncertain, returning JSON result: $fromJson")
            return fromJson
        }

        // Last resort: return a minimal VideoInfo so processing can still proceed.
        // Unknown codec defaults to stream-copy in TranscodePolicy (fail-open).
        Log.w(TAG, "All inspection methods failed for ${file.name} — returning minimal VideoInfo")
        return VideoInfo(
            videoCodec = "unknown",
            width = null, height = null, fps = null, pixFmt = null,
            videoBitrate = null, videoIndex = null,
            audioCodec = "unknown",
            sampleRate = null, audioBitrate = null, audioChannels = null, audioIndex = null,
            durationSec = null, containerBitrate = null
        )
    }

    // ── Primary: ffprobe -of json ────────────────────────────────────────────

    private fun inspectViaJson(file: File): VideoInfo? {
        return try {
            // Capture ffprobe output via Config log callback
            val outputLines = StringBuilder()
            val prevLevel = Config.getLogLevel()
            Config.enableLogCallback { message ->
                outputLines.append(message.text)
            }

            val rc = FFprobe.execute(arrayOf(
                "-v", "quiet", "-of", "json",
                "-show_streams", "-show_format",
                file.absolutePath
            ))

            Config.enableLogCallback(null)
            Config.setLogLevel(prevLevel)

            if (rc != 0) {
                Log.w(TAG, "ffprobe execute rc=$rc")
                return null
            }

            val raw = outputLines.toString().trim()
            Log.d(TAG, "ffprobe raw output length=${raw.length}")

            // Find JSON boundaries robustly
            val jsonStart = raw.indexOf('{')
            val jsonEnd = raw.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd < 0) {
                Log.w(TAG, "No JSON found in ffprobe output")
                return null
            }

            parseJson(raw.substring(jsonStart, jsonEnd + 1))
        } catch (e: Exception) {
            Log.e(TAG, "inspectViaJson failed: ${e.message}")
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
            when (s.optString("codec_type")) {
                "video" -> if (videoIndex == null) {
                    videoCodec = s.optString("codec_name", "unknown").lowercase()
                    width = s.optInt("width").takeIf { it > 0 }
                    height = s.optInt("height").takeIf { it > 0 }
                    fps = parseFraction(s.optString("r_frame_rate"))
                        ?: parseFraction(s.optString("avg_frame_rate"))
                    pixFmt = s.optString("pix_fmt").ifBlank { null }
                    videoBitrate = s.optString("bit_rate").toLongOrNull()
                    videoIndex = s.optInt("index")
                    // Detect MPEG-TS H.264 (Annex B, not AVCC): is_avc == "false"
                    isTsEncapsulated = s.optString("is_avc") == "false"
                }
                "audio" -> if (audioIndex == null) {
                    audioCodec = s.optString("codec_name").lowercase().ifBlank { null }
                    sampleRate = s.optString("sample_rate").toIntOrNull()
                    audioBitrate = s.optString("bit_rate").toLongOrNull()
                    audioChannels = s.optInt("channels").takeIf { it > 0 }
                    audioIndex = s.optInt("index")
                }
            }
        }

        val fmt = root.optJSONObject("format")
        val durationSec = fmt?.optString("duration")?.toDoubleOrNull()
        val containerBitrate = fmt?.optString("bit_rate")?.toLongOrNull()

        // Also try container bitrate for video if stream didn't have it
        if (videoBitrate == null) videoBitrate = containerBitrate

        return VideoInfo(
            videoCodec, width, height, fps, pixFmt, videoBitrate, videoIndex,
            audioCodec, sampleRate, audioBitrate, audioChannels, audioIndex,
            durationSec, containerBitrate, isTsEncapsulated
        )
    }

    // ── Fallback: Java API ───────────────────────────────────────────────────

    private fun inspectViaApi(file: File): VideoInfo? {
        return try {
            val info = FFprobe.getMediaInformation(file.absolutePath) ?: return null

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
            VideoInfo(
                videoCodec, width, height, fps, pixFmt, videoBitrate, videoIndex,
                audioCodec, sampleRate, audioBitrate, audioChannels, audioIndex,
                durationSec, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "inspectViaApi failed: ${e.message}")
            null
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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

