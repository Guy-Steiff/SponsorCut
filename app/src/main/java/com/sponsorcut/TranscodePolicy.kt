package com.sponsorcut

import android.util.Log

/**
 * Pure decision layer. Consumes VideoInfo, never touches files or ffprobe directly.
 */
object TranscodePolicy {

    private const val TAG = "TranscodePolicy"

    private val COPY_SAFE_VIDEO = setOf("h264", "hevc", "h265", "vp8", "vp9", "av1", "mpeg4")
    private val COPY_SAFE_AUDIO = setOf("aac", "mp3", "opus", "vorbis", "ac3", "eac3", "flac", "pcm_s16le")

    enum class ComplexityTier { LOW, MEDIUM, HIGH }

    private data class BitrateDecision(
        val sourceStreamBitrate: Long?,
        val sourceFormatBitrate: Long?,
        val selectedSourceBitrate: Long?,
        val targetBitrate: Long,
        val decisionReason: String,
        val warning: String? = null
    )

    /**
     * SINGLE DECISION GRAPH: All copy/transcode decisions originate here and ONLY here.
     *
     * This is the CANONICAL SOURCE OF TRUTH. Both SW and HW/FAST paths must consume
     * pre-computed canCopyVideo and canCopyAudio directly, never recomputing them.
     *
     * ARCHITECTURE RULE (ENFORCED):
     * - canCopyVideo and canCopyAudio are computed ONCE by this function
     * - All code paths access the final boolean decisions, not intermediate raw fields
     * - No formula duplication → no drift possible
     *
     * If copy/transcode logic must change, modify ONLY this class and its computation.
     */
    private data class CoreDecisions(
        // Raw codec compatibility inputs
        val isAudioOnly: Boolean,
        val videoCodecKnownBad: Boolean,
        val videoIsCopyable: Boolean,
        val audioCodecKnownBad: Boolean,
        val forceReEncode: Boolean,
        // FINAL CANONICAL DECISIONS (computed once, used everywhere)
        val canCopyVideo: Boolean,
        val canCopyAudio: Boolean
    )

    data class ProcessingPlan(
        val canCopyVideo: Boolean,
        val canCopyAudio: Boolean,
        /** Complete ffmpeg -c:v … args for video re-encode. Empty when canCopyVideo=true. */
        val videoEncoderArgs: List<String>,
        /** Conservative fallback video args if source-matched args fail at runtime. */
        val fallbackVideoEncoderArgs: List<String>,
        /** Args inserted before -i for HW-accelerated decode. Empty = SW decode. */
        val hwDecoderArgs: List<String>,
        /** Complete ffmpeg -c:a … args for audio re-encode. Empty when canCopyAudio=true. */
        val audioEncoderArgs: List<String>,
        /** Conservative fallback audio args if source-matched args fail at runtime. */
        val fallbackAudioEncoderArgs: List<String>,
        /** Probed output extension to enforce at output write-time. */
        val outputExtension: String,
        /** Optional FFmpeg muxer to force (e.g. mp4, matroska, webm). */
        val outputMuxer: String?,
        val complexity: ComplexityTier,
        val rationale: String,
        val useSlowSeek: Boolean = false,
        /**
         * When true FfmpegEngine uses a single filter_complex pass (trim+concat) instead of
         * per-segment processing. MediaCodec is initialised exactly once → dramatically faster
         * for HW-Accurate mode. Per-segment progress is replaced by elapsed-time display.
         */
        val singlePassFilter: Boolean = false
    )

    /**
     * Derive the SINGLE DECISION GRAPH for all copy/transcode decisions.
     *
     * This function computes:
     * 1. Raw codec compatibility inputs (videoCodecKnownBad, etc.)
     * 2. FINAL CANONICAL DECISIONS: canCopyVideo, canCopyAudio
     *
     * Both SW and HW/FAST paths consume the returned CoreDecisions object.
     * They NEVER recompute these formulas locally.
     *
     * ENFORCEMENT: If any path recomputes canCopyVideo or canCopyAudio,
     * that is a violation of the single decision graph rule.
     */
    private fun deriveCoreDecisions(
        info: FFProbeInspector.VideoInfo,
        frameAccurate: Boolean,
        hwAccurate: Boolean
    ): CoreDecisions {
        val isAudioOnly = info.videoCodec == "unknown" && info.audioCodec != null
        val forceReEncode = frameAccurate || hwAccurate

        val videoCodecKnownBad = !isAudioOnly
            && info.videoCodec != "unknown"
            && info.videoCodec !in COPY_SAFE_VIDEO

        val videoIsCopyable = isAudioOnly || !videoCodecKnownBad

        val audioCodecKnownBad = info.audioCodec != null
            && info.audioCodec != "unknown"
            && info.audioCodec !in COPY_SAFE_AUDIO

        // FINAL CANONICAL DECISIONS: Computed here, used everywhere, never recomputed
        val canCopyVideo = videoIsCopyable && !forceReEncode
        val canCopyAudio = !audioCodecKnownBad && !forceReEncode

        return CoreDecisions(
            isAudioOnly = isAudioOnly,
            videoCodecKnownBad = videoCodecKnownBad,
            videoIsCopyable = videoIsCopyable,
            audioCodecKnownBad = audioCodecKnownBad,
            forceReEncode = forceReEncode,
            canCopyVideo = canCopyVideo,
            canCopyAudio = canCopyAudio
        )
    }

    /**
     * @param frameAccurate  SW-Accurate: mpeg4 CPU encoder, single filter_complex pass.
     *                       Always available (no GPL libs). Uses the shared target bitrate policy.
     * @param hwAccurate     HW-Accurate: h264_mediacodec single filter_complex pass.
     *                       MediaCodec initialised once → much faster on capable devices.
     */
    fun plan(
        info: FFProbeInspector.VideoInfo,
        frameAccurate: Boolean,
        hwAccurate: Boolean = false
    ): ProcessingPlan {
        val reasons = mutableListOf<String>()

        // SINGLE DECISION GRAPH: Derive all copy/transcode decisions ONCE
        // Both SW and HW/FAST paths consume these finalized boolean decisions.
        // They never recompute canCopyVideo or canCopyAudio locally.
        val decisions = deriveCoreDecisions(info, frameAccurate, hwAccurate)

        if (decisions.videoCodecKnownBad) reasons += "video codec '${info.videoCodec}' requires transcode"
        if (!decisions.isAudioOnly && decisions.forceReEncode && decisions.videoIsCopyable) {
            reasons += when {
                hwAccurate    -> "HW-Accurate single-pass (h264_mediacodec, one-shot init)"
                frameAccurate -> "SW-Accurate single-pass (mpeg4 CPU, one-shot encode)"
                else          -> ""
            }
        }
        if (decisions.isAudioOnly) reasons += "audio-only file"
        if (info.videoCodec == "unknown" && !decisions.isAudioOnly) reasons += "codec unknown — defaulting to copy"

        if (decisions.audioCodecKnownBad) reasons += "audio codec '${info.audioCodec}' requires transcode"
        // If SW (frameAccurate) is requested, build and return an isolated SW plan here.
        // SW must be fully isolated from the HW/FAST decision logic below.
        if (frameAccurate) {
            return buildSwPlan(info, decisions, reasons)
        }

        // SW-Accurate uses mpeg4 (CPU, always available, no GPL libs needed).
        // HW-Accurate uses h264_mediacodec (Android MediaCodec).
        // The single-pass filter_complex architecture is the same for both.
        val emptyArgs: List<String> = emptyList()
        val modeLabel = if (hwAccurate) "HW" else "FAST"

        // Bitrate decision / logging: compute once for HW/FAST modes only.
        val bitrateDecision = computeTargetBitrate(info)
        reasons += buildString {
            append(
                "bitrate policy=${bitrateDecision.decisionReason} " +
                    "[stream=${formatBitrateLabel(bitrateDecision.sourceStreamBitrate)}, " +
                    "format=${formatBitrateLabel(bitrateDecision.sourceFormatBitrate)}, " +
                    "target=${formatBitrateLabel(bitrateDecision.targetBitrate)}]"
            )
            bitrateDecision.warning?.let { append("; $it") }
        }

        val (videoEncoderArgs, fallbackVideoEncoderArgs) = when {
            decisions.isAudioOnly || decisions.canCopyVideo -> emptyArgs to emptyArgs
            // ...existing code...
            else -> {
                // HW encode (hwAccurate) or forced transcode due to unsupported codec.
                // HW encoder does not interpret bitrate as a quality target; device encoders
                // configure parameters according to implementation constraints which may
                // lead to quality variance between devices.
                val targetBps = bitrateDecision.targetBitrate
                val bitrateKbps = (targetBps / 1000L).coerceAtLeast(1L)
                val sourcePixFmt = inferHwPixFmt(info.pixFmt)
                listOf("-c:v", "h264_mediacodec", "-b:v", "${bitrateKbps}k", "-pix_fmt", sourcePixFmt) to
                    listOf("-c:v", "h264_mediacodec", "-b:v", "${bitrateKbps}k", "-pix_fmt", "yuv420p")
            }
        }

        val fallbackAudioEncoderArgs = listOf("-c:a", "aac", "-b:a", "128k")
        val audioEncoderArgs = when {
            decisions.canCopyAudio -> emptyList()
            else -> inferAudioEncoderArgs(info) ?: fallbackAudioEncoderArgs
        }

        val (outputExtension, outputMuxer) = inferOutputContainer(
            info = info,
            forceReEncode = decisions.forceReEncode,
            canCopyVideo = decisions.canCopyVideo,
            canCopyAudio = decisions.canCopyAudio,
            isAudioOnly = decisions.isAudioOnly,
            audioEncoderArgs = audioEncoderArgs
        )
        reasons += "output container=${outputExtension}${if (outputMuxer != null) " ($outputMuxer)" else ""}"

        // hwDecoderArgs are unused — both modes use single-pass filter_complex now.
        val hwDecoderArgs: List<String> = emptyList()

        val singlePassFilter = (hwAccurate) && !decisions.isAudioOnly && !decisions.canCopyVideo

        val pixels2 = info.pixels ?: 0
        val fps = info.fps ?: 30f
        val complexity = when {
            decisions.isAudioOnly -> ComplexityTier.LOW
            decisions.forceReEncode && pixels2 >= 1920 * 1080 && fps >= 50f -> ComplexityTier.HIGH
            decisions.forceReEncode || pixels2 >= 1920 * 1080 -> ComplexityTier.MEDIUM
            else -> ComplexityTier.LOW
        }

        val useSlowSeek = info.isTsEncapsulated
        if (useSlowSeek) reasons += "TS-encapsulated — using slow seek + stream maps"

        val rationale = if (reasons.isEmpty()) "stream copy (fast)" else reasons.joinToString("; ")
        Log.i(TAG, "Plan: canCopyVideo=${decisions.canCopyVideo} canCopyAudio=${decisions.canCopyAudio} " +
                "singlePass=$singlePassFilter useSlowSeek=$useSlowSeek rationale=$rationale")
        DiagLog.append(
            "BITRATE POLICY",
            buildString {
                append("mode=$modeLabel ")
                append("resolution=${info.width ?: 0}x${info.height ?: 0} ")
                append("source_stream=${formatBitrateLabel(bitrateDecision.sourceStreamBitrate)} ")
                append("source_format=${formatBitrateLabel(bitrateDecision.sourceFormatBitrate)} ")
                append("decision=${bitrateDecision.decisionReason} ")
                append("final_target=${formatBitrateLabel(bitrateDecision.targetBitrate)}")
                bitrateDecision.warning?.let { append(" warning=$it") }
            }
        )
        if (videoEncoderArgs.isNotEmpty()) {
            DiagLog.append(
                "BITRATE POLICY",
                "final_video_args=${videoEncoderArgs.joinToString(" ")} fallback_video_args=${fallbackVideoEncoderArgs.joinToString(" ")}"
            )
        }
         if (audioEncoderArgs.isNotEmpty()) {
             DiagLog.append(
                 "BITRATE POLICY",
                 "final_audio_args=${audioEncoderArgs.joinToString(" ")} fallback_audio_args=${fallbackAudioEncoderArgs.joinToString(" ")}"
             )
         }

         return ProcessingPlan(
             canCopyVideo     = decisions.canCopyVideo,
             canCopyAudio     = decisions.canCopyAudio,
             videoEncoderArgs = videoEncoderArgs,
             fallbackVideoEncoderArgs = fallbackVideoEncoderArgs,
             hwDecoderArgs    = hwDecoderArgs,
             audioEncoderArgs = audioEncoderArgs,
             fallbackAudioEncoderArgs = fallbackAudioEncoderArgs,
             outputExtension = outputExtension,
             outputMuxer = outputMuxer,
             complexity       = complexity,
             rationale        = rationale,
             useSlowSeek      = useSlowSeek,
             singlePassFilter = singlePassFilter
         )
     }

    private fun inferSwPixFmt(source: String?): String {
        val fmt = source?.lowercase() ?: return "yuv420p"
        return when {
            fmt.startsWith("yuv420") || fmt.startsWith("yuvj420") -> "yuv420p"
            fmt.startsWith("yuv422") -> "yuv422p"
            fmt.startsWith("yuv444") -> "yuv444p"
            else -> "yuv420p"
        }
    }

    private fun inferHwPixFmt(source: String?): String {
        val fmt = source?.lowercase() ?: return "yuv420p"
        return when {
            fmt.startsWith("yuv420") || fmt.startsWith("yuvj420") -> "yuv420p"
            // Most MediaCodec stacks accept yuv420p reliably; keep fallback conservative.
            else -> "yuv420p"
        }
    }

    private fun inferAudioEncoderArgs(info: FFProbeInspector.VideoInfo): List<String>? {
        val codec = info.audioCodec?.lowercase() ?: return null
        val sourceKbps = info.audioBitrate?.div(1000)?.toInt()
        val clamped = sourceKbps?.coerceIn(48, 512)
        return when (codec) {
            "aac" -> listOf("-c:a", "aac", "-b:a", "${clamped ?: 128}k")
            "opus" -> listOf("-c:a", "opus", "-b:a", "${(clamped ?: 128).coerceIn(64, 256)}k")
            "vorbis" -> listOf("-c:a", "vorbis", "-b:a", "${(clamped ?: 128).coerceIn(64, 320)}k")
            "mp3" -> listOf("-c:a", "mp3", "-b:a", "${(clamped ?: 192).coerceIn(96, 320)}k")
            "flac" -> listOf("-c:a", "flac")
            "ac3", "eac3" -> listOf("-c:a", "ac3", "-b:a", "${(clamped ?: 192).coerceIn(96, 640)}k")
            "pcm_s16le" -> listOf("-c:a", "pcm_s16le")
            else -> null
        }
    }

    /**
     * SINGLE SOURCE OF TRUTH FOR FAST + HW MODES ONLY.
     * This policy is mode-agnostic and returns an inferred BitrateDecision based on ffprobe
     * data (stream bitrate, container bitrate) or resolution fallback when metadata is absent.
     * MediaCodec does NOT interpret bitrate as a quality intent; it only configures encoder
     * parameters and implementation differences between devices may cause quality variance.
     */
    private fun computeTargetBitrate(
        info: FFProbeInspector.VideoInfo
    ): BitrateDecision {
        val sourceStream = info.videoBitrate?.takeIf { it > 0L }
        val sourceFormat = info.containerBitrate?.takeIf { it > 0L }
        val source = sourceStream ?: sourceFormat

        val target = if (source != null) {
            val floor = knownSourceFloorBitrate(info)
            val maxAllowed = (source * 1.5).toLong().coerceAtLeast(source)
            source.coerceAtLeast(floor).coerceAtMost(maxAllowed)
        } else {
            fallbackBitrate(info)
        }

        val decisionReason = when {
            source == null -> "used resolution fallback"
            target == source -> "used source bitrate"
            else -> "used clamped source bitrate"
        }

        val overshootWarning = if (source != null && target > source * 5) {
            "BITRATE OVERSHOOT DETECTED target=${formatBitrateLabel(target)} source=${formatBitrateLabel(source)}"
        } else null

        return BitrateDecision(
            sourceStreamBitrate = sourceStream,
            sourceFormatBitrate = sourceFormat,
            selectedSourceBitrate = source,
            targetBitrate = target,
            decisionReason = decisionReason,
            warning = overshootWarning
        )
    }

    // Build a ProcessingPlan for SW-Accurate mode (frameAccurate). This path is intentionally
    // isolated from the HW/FAST policy-driven logic and uses the deterministic legacy
    // resolution-based bitrate ladder.
    //
    // ARCHITECTURE: SW must consume the same CoreDecisions as HW/FAST to prevent policy drift.
    // The only difference is the bitrate model (legacy resolution ladder vs. computed bitrate policy).
    //
    // CRITICAL: SW does NOT recompute canCopyVideo/canCopyAudio.
    // It uses the finalized boolean decisions from CoreDecisions.
    // This enforces the single decision graph at the output level.
    private fun buildSwPlan(
        info: FFProbeInspector.VideoInfo,
        decisions: CoreDecisions,
        reasons: MutableList<String>
    ): ProcessingPlan {
        // CRITICAL: No recomputation of canCopyVideo/canCopyAudio here.
        // Both paths consume the SAME finalized decisions from CoreDecisions.

        if (decisions.videoCodecKnownBad) reasons += "video codec '${info.videoCodec}' requires transcode"
        if (decisions.isAudioOnly) reasons += "audio-only file"

        if (decisions.audioCodecKnownBad) reasons += "audio codec '${info.audioCodec}' requires transcode"

        val emptyArgs: List<String> = emptyList()

        // Compute SW legacy bitrate exactly once (kbps) and convert to bps for logging.
        val swKbps = swLegacyBitrate(info)
        val swBps = swKbps.toLong() * 1000L

        val (videoEncoderArgs, fallbackVideoEncoderArgs) = when {
            decisions.isAudioOnly || decisions.canCopyVideo -> emptyArgs to emptyArgs
            else -> {
                val sourcePixFmt = inferSwPixFmt(info.pixFmt)
                listOf(
                    "-c:v", "mpeg4",
                    "-b:v", "${swKbps}k",
                    "-pix_fmt", sourcePixFmt
                ) to listOf(
                    "-c:v", "mpeg4",
                    "-b:v", "${swKbps}k",
                    "-pix_fmt", "yuv420p"
                )
            }
        }

        val fallbackAudioEncoderArgs = listOf("-c:a", "aac", "-b:a", "128k")
        val audioEncoderArgs = when {
            decisions.canCopyAudio -> emptyList()
            else -> inferAudioEncoderArgs(info) ?: fallbackAudioEncoderArgs
        }

        val (outputExtension, outputMuxer) = inferOutputContainer(
            info = info,
            forceReEncode = decisions.forceReEncode,
            canCopyVideo = decisions.canCopyVideo,
            canCopyAudio = decisions.canCopyAudio,
            isAudioOnly = decisions.isAudioOnly,
            audioEncoderArgs = audioEncoderArgs
        )
        reasons += "output container=${outputExtension}${if (outputMuxer != null) " ($outputMuxer)" else ""}"

        val hwDecoderArgs: List<String> = emptyList()
        val singlePassFilter = true && !decisions.isAudioOnly && !decisions.canCopyVideo

        val pixels2 = info.pixels ?: 0
        val fps = info.fps ?: 30f
        val complexity = when {
            decisions.isAudioOnly -> ComplexityTier.LOW
            pixels2 >= 1920 * 1080 && fps >= 50f -> ComplexityTier.HIGH
            pixels2 >= 1920 * 1080 -> ComplexityTier.MEDIUM
            else -> ComplexityTier.LOW
        }

        val useSlowSeek = info.isTsEncapsulated
        if (useSlowSeek) reasons += "TS-encapsulated — using slow seek + stream maps"

        val rationale = if (reasons.isEmpty()) "stream copy (fast)" else reasons.joinToString("; ")
        Log.i(TAG, "SW Plan: canCopyVideo=${decisions.canCopyVideo} canCopyAudio=${decisions.canCopyAudio} singlePass=$singlePassFilter useSlowSeek=$useSlowSeek rationale=$rationale")

        DiagLog.append(
            "BITRATE POLICY",
            "mode=SW resolution=${info.width ?: 0}x${info.height ?: 0} final_target=${formatBitrateLabel(swBps)}"
        )

        if (videoEncoderArgs.isNotEmpty()) {
            DiagLog.append(
                "BITRATE POLICY",
                "final_video_args=${videoEncoderArgs.joinToString(" ")} fallback_video_args=${fallbackVideoEncoderArgs.joinToString(" ") }"
            )
        }
         if (audioEncoderArgs.isNotEmpty()) {
             DiagLog.append(
                 "BITRATE POLICY",
                 "final_audio_args=${audioEncoderArgs.joinToString(" ")} fallback_audio_args=${fallbackAudioEncoderArgs.joinToString(" ") }"
             )
         }

         return ProcessingPlan(
             canCopyVideo     = decisions.canCopyVideo,
             canCopyAudio     = decisions.canCopyAudio,
             videoEncoderArgs = videoEncoderArgs,
             fallbackVideoEncoderArgs = fallbackVideoEncoderArgs,
             hwDecoderArgs    = hwDecoderArgs,
             audioEncoderArgs = audioEncoderArgs,
             fallbackAudioEncoderArgs = fallbackAudioEncoderArgs,
             outputExtension = outputExtension,
             outputMuxer = outputMuxer,
             complexity       = complexity,
             rationale        = rationale,
             useSlowSeek      = useSlowSeek,
             singlePassFilter = singlePassFilter
         )
     }

    private fun knownSourceFloorBitrate(info: FFProbeInspector.VideoInfo): Long {
        val pixels = info.pixels ?: 0
        val kbps = when {
            pixels >= 1920 * 1080 -> 1500
            pixels >= 1280 * 720  -> 800
            pixels >= 854 * 480   -> 400
            else                  -> 250
        }
        return kbps * 1000L
    }

    private fun fallbackBitrate(info: FFProbeInspector.VideoInfo): Long {
        val pixels = info.pixels ?: 0
        val kbps = when {
            pixels >= 1920 * 1080 -> 1500
            pixels >= 1280 * 720  -> 1000
            pixels >= 854 * 480   -> 600
            else                  -> 400
        }
        return kbps * 1000L
    }

    private fun swLegacyBitrate(info: FFProbeInspector.VideoInfo): Int {
        val pixels = info.pixels ?: 0

        return when {
            pixels >= 1920 * 1080 -> 1500
            pixels >= 1280 * 720  -> 1000
            pixels >= 854 * 480   -> 600
            else                  -> 400
        }
    }

    /**
     * CONTAINER POLICY (FIRST-CLASS DECISION)
     *
     * This is NOT a formatting utility; it is a critical POLICY FUNCTION.
     * All container selection logic for both SW and HW/FAST paths originates here.
     *
     * ARCHITECTURE RULE: Any future container selection decision MUST be made
     * by modifying this function. Do not branch container logic elsewhere.
     *
     * Inputs are:
     *   - forceReEncode: whether re-encoding is happening (affects MP4 normalization)
     *   - canCopyVideo / canCopyAudio: codec compatibility decisions
     *   - isAudioOnly: determines audio-only vs video container logic
     *   - audioEncoderArgs: used to infer target audio codec for audio-only re-encodes
     *
     * This ensures container decisions are deterministic, auditable, and versioned
     * alongside core encoding policy.
     */
    private fun inferOutputContainer(
        info: FFProbeInspector.VideoInfo,
        forceReEncode: Boolean,
        canCopyVideo: Boolean,
        canCopyAudio: Boolean,
        isAudioOnly: Boolean,
        audioEncoderArgs: List<String>
    ): Pair<String, String?> {
        val formats = info.containerFormat
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

        // Re-encoded video is normalised to MP4 for the broadest mux/decoder compatibility,
        // especially for HW-Accurate (h264_mediacodec) outputs from WEBM/Matroska sources.
        if (forceReEncode && !canCopyVideo) return "mp4" to "mp4"

        // Audio-only re-encode uses container best matching the target audio codec.
        if (isAudioOnly && !canCopyAudio) {
            val targetAudioCodec = audioCodecFromArgs(audioEncoderArgs)
            return when (targetAudioCodec) {
                "opus", "vorbis" -> "ogg" to "ogg"
                "mp3" -> "mp3" to "mp3"
                "flac" -> "flac" to "flac"
                "pcm_s16le" -> "wav" to "wav"
                else -> "m4a" to "mp4"
            }
        }

        return when {
            // ffprobe often reports "matroska,webm" for WEBM files; prefer WEBM in copy paths.
            "webm" in formats -> "webm" to "webm"
            "matroska" in formats -> "mkv" to "matroska"
            "mov" in formats || "mp4" in formats || "m4a" in formats ||
                "3gp" in formats || "3g2" in formats || "mj2" in formats -> {
                if (isAudioOnly) "m4a" to "mp4" else "mp4" to "mp4"
            }
            "ogg" in formats -> if (info.audioCodec == "opus") "opus" to "ogg" else "ogg" to "ogg"
            "mp3" in formats -> "mp3" to "mp3"
            "flac" in formats -> "flac" to "flac"
            "wav" in formats -> "wav" to "wav"
            "mpegts" in formats -> "ts" to "mpegts"
            else -> if (isAudioOnly) "m4a" to "mp4" else "mp4" to "mp4"
        }
    }

    private fun audioCodecFromArgs(args: List<String>): String? {
        val idx = args.indexOf("-c:a")
        return if (idx >= 0 && idx + 1 < args.size) args[idx + 1].lowercase() else null
    }

    private fun formatBitrateLabel(rawBps: Long?): String {
        if (rawBps == null || rawBps <= 0L) return "null"
        return "${rawBps / 1000}kbps"
    }
}
