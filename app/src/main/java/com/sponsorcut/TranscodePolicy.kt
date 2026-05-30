package com.sponsorcut

import android.util.Log

/**
 * Pure decision layer. Consumes VideoInfo, never touches files or ffprobe directly.
 * Decides HOW to transcode based on actual media properties.
 */
object TranscodePolicy {

    private const val TAG = "TranscodePolicy"

    /**
     * Codecs that can be stream-copied without re-encode.
     * Derived from what the bundled mobile-ffmpeg-full-gpl supports in MP4/MKV containers.
     */
    private val COPY_SAFE_VIDEO = setOf("h264", "hevc", "h265", "vp8", "vp9", "av1", "mpeg4")
    private val COPY_SAFE_AUDIO = setOf("aac", "mp3", "opus", "vorbis", "ac3", "eac3", "flac", "pcm_s16le")

    /** How complex is this video to process? Affects UI messaging only — not codec choice. */
    enum class ComplexityTier { LOW, MEDIUM, HIGH }

    data class ProcessingPlan(
        /** Use stream copy (-c copy) for video if true; else re-encode with h264_mediacodec */
        val canCopyVideo: Boolean,
        /** Use stream copy for audio if true; else re-encode with aac */
        val canCopyAudio: Boolean,
        /** Video encoder to use if re-encoding */
        val videoEncoder: String,
        /** Audio encoder to use if re-encoding */
        val audioEncoder: String,
        /** Pixel format to force on re-encode (null = let ffmpeg decide) */
        val pixFmt: String?,
        /** Target video bitrate in kbps for re-encode (used by MediaCodec encoder) */
        val videoBitrateKbps: Int,
        /** Estimated processing complexity for UI feedback */
        val complexity: ComplexityTier,
        /** Human-readable rationale (for logging/debug) */
        val rationale: String,
        /** Use slow seek (-ss after -i) + -fflags +genpts + explicit stream maps.
         *  Required for MPEG-TS encapsulated files (e.g. Rumble downloads) to prevent
         *  concat timestamp drift. Slower but correct. */
        val useSlowSeek: Boolean = false
    )

    /**
     * Given real media info, decide the best transcode strategy.
     *
     * @param frameAccurate  User explicitly requested frame-accurate mode (forces re-encode).
     */
    fun plan(info: FFProbeInspector.VideoInfo, frameAccurate: Boolean): ProcessingPlan {
        val reasons = mutableListOf<String>()

        // --- Audio-only file (no video stream) ---
        val isAudioOnly = info.videoCodec == "unknown" && info.audioCodec != null

        // --- Video copy eligibility ---
        // "unknown" means ffprobe couldn't identify the codec — default to copy (safe for most containers).
        // Only force re-encode when we KNOW the codec is not copy-safe.
        val videoCodecKnownBad = !isAudioOnly
            && info.videoCodec != "unknown"
            && info.videoCodec !in COPY_SAFE_VIDEO
        val videoIsCopyable = isAudioOnly || !videoCodecKnownBad
        val canCopyVideo = videoIsCopyable && !frameAccurate
        if (videoCodecKnownBad) reasons += "video codec '${info.videoCodec}' requires transcode"
        if (!isAudioOnly && frameAccurate && videoIsCopyable) reasons += "frame-accurate mode forces re-encode"
        if (isAudioOnly) reasons += "audio-only file"
        if (info.videoCodec == "unknown" && !isAudioOnly) reasons += "codec unknown — defaulting to copy"

        // --- Audio copy eligibility ---
        // Same principle: unknown codec → copy, not re-encode
        val audioCodecKnownBad = info.audioCodec != null
            && info.audioCodec != "unknown"
            && info.audioCodec !in COPY_SAFE_AUDIO
        val canCopyAudio = !audioCodecKnownBad && !frameAccurate
        if (audioCodecKnownBad) reasons += "audio codec '${info.audioCodec}' requires transcode"

        // --- Encoder choices (only relevant when re-encoding) ---
        // h264_mediacodec = Android hardware H264 encoder (always available, LGPL-safe, fast)
        val videoEncoder = "h264_mediacodec"
        val audioEncoder = "aac"

        // --- Target video bitrate for h264_mediacodec (which uses -b:v, not -crf) ---
        // Prefer source bitrate; fall back to resolution-based heuristic.
        val sourceBitrateKbps = (info.videoBitrate ?: info.containerBitrate)?.div(1000)?.toInt()
        val pixels = info.pixels ?: 0
        val fallbackKbps = when {
            pixels >= 1920 * 1080 -> 4000
            pixels >= 1280 * 720  -> 2000
            pixels >= 854 * 480   -> 1000
            pixels > 0            -> 600
            else                  -> 1500
        }
        val videoBitrateKbps = sourceBitrateKbps?.coerceAtLeast(300) ?: fallbackKbps

        // --- Pixel format ---
        // h264_mediacodec handles yuv420p natively; force it only when re-encoding to
        // ensure compatibility with all players.
        val pixFmt = if (!canCopyVideo && !isAudioOnly) "yuv420p" else null

        // --- Complexity tier ---
        val pixels2 = info.pixels ?: 0
        val fps = info.fps ?: 30f
        val complexity = when {
            isAudioOnly -> ComplexityTier.LOW
            frameAccurate && pixels2 >= 1920 * 1080 && fps >= 50f -> ComplexityTier.HIGH
            frameAccurate || pixels2 >= 1920 * 1080 -> ComplexityTier.MEDIUM
            else -> ComplexityTier.LOW
        }

        // --- TS encapsulation detection ---
        // MPEG-TS wrapped in .mp4 (Rumble, some broadcasts) needs slow seeking and
        // explicit stream maps even in stream-copy fast mode to avoid timestamp drift.
        val useSlowSeek = info.isTsEncapsulated
        if (useSlowSeek) reasons += "TS-encapsulated (Annex B) — using slow seek + stream maps"

        val rationale = if (reasons.isEmpty()) "stream copy (fast)" else reasons.joinToString("; ")
        Log.i(TAG, "Plan: canCopyVideo=$canCopyVideo canCopyAudio=$canCopyAudio " +
                "useSlowSeek=$useSlowSeek complexity=$complexity videoBitrateKbps=$videoBitrateKbps rationale=$rationale")

        return ProcessingPlan(
            canCopyVideo = canCopyVideo,
            canCopyAudio = canCopyAudio,
            videoEncoder = videoEncoder,
            audioEncoder = audioEncoder,
            pixFmt = pixFmt,
            videoBitrateKbps = videoBitrateKbps,
            complexity = complexity,
            rationale = rationale,
            useSlowSeek = useSlowSeek
        )
    }
}

