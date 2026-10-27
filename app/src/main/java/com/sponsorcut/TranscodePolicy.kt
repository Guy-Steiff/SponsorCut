package com.sponsorcut

import com.sponsorcut.core.model.ExecutionMode
import com.sponsorcut.core.model.JobKind
import com.sponsorcut.core.model.StreamSelection
import kotlin.math.roundToInt

object TranscodePolicy {

    enum class ComplexityTier { LOW, MEDIUM, HIGH }

    data class ProcessingPlan(
        val canCopyVideo: Boolean,
        val canCopyAudio: Boolean,
        val videoEncoderArgs: List<String>,
        val fallbackVideoEncoderArgs: List<String>,
        val hwDecoderArgs: List<String>,
        val audioEncoderArgs: List<String>,
        val fallbackAudioEncoderArgs: List<String>,
        val outputExtension: String,
        val outputMuxer: String?,
        val complexity: ComplexityTier,
        val rationale: String,
        val executionMode: ExecutionMode,
        val useSlowSeek: Boolean = false,
        val singlePassFilter: Boolean = false,
        val dropVideo: Boolean = false,
        val dropAudio: Boolean = false,
        val jobKind: JobKind = JobKind.REMOVE_SEGMENTS,
        val removeSegments: Boolean = true,
        val streamSelection: StreamSelection = StreamSelection.BOTH
    )

    private val VIDEO_CONTAINER_COMPATIBILITY = mapOf(
        "H.264" to setOf("mp4", "mkv", "mov", "ts", "m2ts", "avi"),
        "H.265/HEVC" to setOf("mp4", "mkv", "mov", "ts", "m2ts"),
        "VP9" to setOf("webm", "mkv"),
        "AV1" to setOf("mp4", "mkv", "webm"),
        "MPEG-4 Part 2" to setOf("mp4", "mkv", "avi")
    )

    private val AUDIO_CONTAINER_COMPATIBILITY = mapOf(
        "AAC" to setOf("mp4", "mkv", "mov", "m4a", "aac", "ts", "m2ts"),
        "Opus" to setOf("webm", "mkv", "opus", "oga", "ogg"),
        "MP3" to setOf("mp3", "mkv", "avi"),
        "Vorbis" to setOf("webm", "mkv", "ogg", "oga"),
        "FLAC" to setOf("mkv", "flac")
    )

    private val DEFAULT_AUDIO_BY_FAMILY = mapOf(
        "H.264" to "aac",
        "H.265/HEVC" to "aac",
        "VP9" to "opus",
        "AV1" to "aac",
        "MPEG-4 Part 2" to "aac"
    )

    private val SW_AUTO_BY_FAMILY = mapOf(
        "H.264" to "libx264",
        "H.265/HEVC" to "libx265",
        "VP9" to "libvpx-vp9",
        "AV1" to "libsvtav1",
        "MPEG-4 Part 2" to "mpeg4"
    )

    private val SW_FALLBACK_FAMILIES = listOf(
        "H.264",
        "VP9",
        "AV1",
        "MPEG-4 Part 2"
    )

    private val HW_AUTO_BY_FAMILY = mapOf(
        "H.264" to listOf("h264_mediacodec"),
        "H.265/HEVC" to listOf("hevc_mediacodec")
    )

    fun plan(
        info: FFProbeInspector.VideoInfo,
        frameAccurate: Boolean,
        hwAccurate: Boolean = false,
        removeSegments: Boolean = true,
        streamSelection: StreamSelection = StreamSelection.BOTH,
        preferences: OutputPreferences = OutputPreferences(),
        catalog: List<EncoderOption> = EncoderCatalog.discover()
    ): ProcessingPlan {
        val mode = when {
            hwAccurate -> ExecutionMode.HW_ACCURATE
            frameAccurate -> ExecutionMode.SW_ACCURATE
            else -> ExecutionMode.FAST
        }

        return if (streamSelection == StreamSelection.BOTH) {
            planBoth(info, mode, removeSegments, preferences, catalog)
        } else {
            planExtraction(info, mode, removeSegments, streamSelection, preferences, catalog)
        }
    }

    private fun planBoth(
        info: FFProbeInspector.VideoInfo,
        mode: ExecutionMode,
        removeSegments: Boolean,
        preferences: OutputPreferences,
        catalog: List<EncoderOption>
    ): ProcessingPlan {
        val reasons = mutableListOf<String>()

        val inputVideoFamily = EncoderCatalog.familyForInputCodec(info.videoCodec)
        val inputAudioFamily = EncoderCatalog.familyForInputCodec(info.audioCodec)

        val videoEncoder = resolveVideoEncoder(
            info, mode, preferences, catalog, inputVideoFamily, reasons
        )
        val audioEncoder = resolveAudioEncoder(
            mode, preferences, catalog, inputAudioFamily, videoEncoder, reasons
        )
        val container = resolveContainer(
            info, preferences, catalog, videoEncoder, audioEncoder, reasons
        )

        val canCopyVideo = mode == ExecutionMode.FAST && videoEncoder == null
        val canCopyAudio = mode == ExecutionMode.FAST && audioEncoder == null

        val quality = resolveQuality(mode, videoEncoder, preferences, info, reasons)

        val videoArgs = buildVideoArgs(
            mode, videoEncoder, quality, preferences.videoFrameRate,
            info.fps, info.height, preferences.videoMaxHeight
        ).toMutableList()

        val audioArgs = buildAudioArgs(
            audioEncoder,
            info,
            preferences.audioSampleRate,
            preferences.audioBitrate
        )

        val outputMuxer = muxerForContainer(container)

        val singlePass = removeSegments && mode != ExecutionMode.FAST

        if (preferences.videoFrameRate != null) {
            reasons += "frame rate explicitly set to ${preferences.videoFrameRate}"
        }

        return ProcessingPlan(
            canCopyVideo = canCopyVideo,
            canCopyAudio = canCopyAudio,
            videoEncoderArgs = videoArgs,
            fallbackVideoEncoderArgs = fallbackVideoArgs(mode, videoEncoder, quality),
            hwDecoderArgs = emptyList(),
            audioEncoderArgs = audioArgs,
            fallbackAudioEncoderArgs = listOf("-c:a", "aac", "-b:a", "128k"),
            outputExtension = container,
            outputMuxer = outputMuxer,
            complexity = complexityFor(info, mode),
            rationale = reasons.joinToString("; ").ifBlank { "input-matched processing" },
            executionMode = mode,
            useSlowSeek = info.isTsEncapsulated,
            singlePassFilter = singlePass,
            removeSegments = removeSegments,
            streamSelection = StreamSelection.BOTH
        )
    }

    private fun planExtraction(
        info: FFProbeInspector.VideoInfo,
        mode: ExecutionMode,
        removeSegments: Boolean,
        streamSelection: StreamSelection,
        preferences: OutputPreferences,
        catalog: List<EncoderOption>
    ): ProcessingPlan {
        val reasons = mutableListOf<String>()
        val empty = emptyList<String>()

        if (streamSelection == StreamSelection.AUDIO_ONLY) {
            val inputFamily = EncoderCatalog.familyForInputCodec(info.audioCodec)
            val encoder = resolveAudioEncoder(
                mode, preferences, catalog, inputFamily, null, reasons
            )
            val canCopy = mode == ExecutionMode.FAST && encoder == null
            // The Audio container dropdown's first entry means "retain the
            // input-derived transcode policy"; it is serialized as null.
            // Otherwise, use the user-selected audio-only container.
            val container = preferences.audioContainer
                ?: preferences.container.takeIf {
                    preferences.audioSectionMode == SectionMode.CUSTOM
                }
                ?: audioContainerFor(encoder ?: info.audioCodec)

            return ProcessingPlan(
                canCopyVideo = false,
                canCopyAudio = canCopy,
                videoEncoderArgs = empty,
                fallbackVideoEncoderArgs = empty,
                hwDecoderArgs = empty,
                audioEncoderArgs = buildAudioArgs(
                    encoder,
                    info,
                    preferences.audioSampleRate,
                    preferences.audioBitrate
                ),
                fallbackAudioEncoderArgs = listOf("-c:a", "aac", "-b:a", "128k"),
                outputExtension = container,
                outputMuxer = muxerForContainer(container),
                complexity = ComplexityTier.LOW,
                rationale = reasons.joinToString("; ").ifBlank { "audio extraction" },
                executionMode = mode,
            useSlowSeek = info.isTsEncapsulated,
                singlePassFilter = removeSegments && !canCopy,
                dropVideo = true,
                dropAudio = false,
                jobKind = JobKind.EXTRACT_AUDIO,
                removeSegments = removeSegments,
                streamSelection = streamSelection
            )
        }

        val inputFamily = EncoderCatalog.familyForInputCodec(info.videoCodec)
        val encoder = resolveVideoEncoder(
            info, mode, preferences, catalog, inputFamily, reasons
        )
        val canCopy = mode == ExecutionMode.FAST && encoder == null
        val quality = resolveQuality(mode, encoder, preferences, info, reasons)
        val container = preferences.container.takeIf {
            preferences.videoSectionMode == SectionMode.CUSTOM
        } ?: videoContainerFor(encoder ?: info.videoCodec)

        return ProcessingPlan(
            canCopyVideo = canCopy,
            canCopyAudio = false,
            videoEncoderArgs = buildVideoArgs(
                mode, encoder, quality, preferences.videoFrameRate,
                info.fps, info.height, preferences.videoMaxHeight
            ),
            fallbackVideoEncoderArgs = fallbackVideoArgs(mode, encoder, quality),
            hwDecoderArgs = emptyList(),
            audioEncoderArgs = empty,
            fallbackAudioEncoderArgs = empty,
            outputExtension = container,
            outputMuxer = muxerForContainer(container),
            complexity = complexityFor(info, mode),
            rationale = reasons.joinToString("; ").ifBlank { "video extraction" },
            executionMode = mode,
            useSlowSeek = info.isTsEncapsulated,
            singlePassFilter = removeSegments && !canCopy,
            dropVideo = false,
            dropAudio = true,
            jobKind = JobKind.EXTRACT_VIDEO,
            removeSegments = removeSegments,
            streamSelection = streamSelection
        )
    }

    fun resolveVideoEncoderForUi(
        info: FFProbeInspector.VideoInfo,
        mode: ExecutionMode,
        preferences: OutputPreferences,
        catalog: List<EncoderOption>,
        inputFamily: String?
    ): String? {
        return try {
            resolveVideoEncoder(
                info,
                mode,
                preferences,
                catalog,
                inputFamily,
                mutableListOf()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveVideoEncoder(
        info: FFProbeInspector.VideoInfo,
        mode: ExecutionMode,
        preferences: OutputPreferences,
        catalog: List<EncoderOption>,
        inputFamily: String?,
        reasons: MutableList<String>
    ): String? {
        if (mode == ExecutionMode.FAST) {
            reasons += "Fast stream copy"
            return null
        }

        if (preferences.videoSectionMode == SectionMode.CUSTOM &&
            preferences.videoCodec == null
        ) {
            reasons += "Custom video section had no codec selected; using Auto"
        } else if (preferences.videoSectionMode == SectionMode.CUSTOM) {
            val requested = preferences.videoCodec
                ?: error("Video is Custom but no video codec was selected")
            val option = EncoderCatalog.find(catalog, requested)
                ?: error("Unknown video encoder: $requested")
            if (!option.available) error("Video encoder $requested is unavailable")
            if (mode == ExecutionMode.SW_ACCURATE && option.hardware) {
                error("$requested is a hardware encoder; select HW mode")
            }
            if (mode == ExecutionMode.HW_ACCURATE && !option.hardware) {
                error("$requested is a software encoder; select SW mode")
            }
            reasons += "Custom video encoder: $requested"
            return requested
        }

        val family = inputFamily
            ?: error("No input-matching policy for video codec '${info.videoCodec}'")

        if (mode == ExecutionMode.HW_ACCURATE) {
            val candidates = HW_AUTO_BY_FAMILY[family]
                ?: error("HW Auto is not defined for $family input; choose SW or a custom HW encoder")
            val available = candidates.firstOrNull { name ->
                EncoderCatalog.find(catalog, name)?.available == true
            }

            if (available != null) {
                reasons += "HW Auto matches $family using $available"
                return available
            }

            val softwareFallback = SW_AUTO_BY_FAMILY[family]
            val softwareOption = softwareFallback?.let {
                EncoderCatalog.find(catalog, it)
            }

            if (softwareOption?.available == true) {
                reasons += "No HW encoder for $family; using SW fallback $softwareFallback"
                return softwareFallback
            }

            error("No available HW or SW encoder for $family input")
        }

        val preferred = SW_AUTO_BY_FAMILY[family]
        val orderedFamilies = buildList {
            if (preferred != null) add(family)
            SW_FALLBACK_FAMILIES.forEach { if (it !in this) add(it) }
        }

        for (candidateFamily in orderedFamilies) {
            val candidateName = SW_AUTO_BY_FAMILY[candidateFamily] ?: continue
            val option = EncoderCatalog.find(catalog, candidateName)
            if (option?.available == true) {
                reasons += if (candidateFamily == family) {
                    "SW Auto matches $family using $candidateName"
                } else {
                    "SW Auto fallback: $family -> $candidateName"
                }
                return candidateName
            }
        }

        error("No available SW video encoder matches the fallback hierarchy")
    }

    private fun resolveAudioEncoder(
        mode: ExecutionMode,
        preferences: OutputPreferences,
        catalog: List<EncoderOption>,
        inputFamily: String?,
        videoEncoder: String?,
        reasons: MutableList<String>
    ): String? {
        if (mode == ExecutionMode.FAST) {
            reasons += "Fast audio stream copy"
            return null
        }

        if (preferences.audioSectionMode == SectionMode.CUSTOM) {
            val requested = preferences.audioCodec
                ?: error("Audio is Custom but no audio codec was selected")
            val option = EncoderCatalog.find(catalog, requested)
                ?: error("Unknown audio encoder: $requested")
            if (!option.available) error("Audio encoder $requested is unavailable")
            reasons += "Custom audio encoder: $requested"
            return requested
        }

        val inputEncoder = when (inputFamily) {
            "AAC" -> "aac"
            "Opus" -> "libopus"
            "MP3" -> "libmp3lame"
            "Vorbis" -> "libvorbis"
            "FLAC" -> "flac"
            else -> null
        }

        if (inputEncoder != null &&
            EncoderCatalog.find(catalog, inputEncoder)?.available == true
        ) {
            reasons += "Audio Auto matches input family $inputFamily using $inputEncoder"
            return inputEncoder
        }

        val videoFamily = videoEncoder?.let {
            EncoderCatalog.find(catalog, it)?.codecFamily
        }
        val fallback = DEFAULT_AUDIO_BY_FAMILY[videoFamily] ?: "aac"
        val fallbackOption = EncoderCatalog.find(catalog, fallback)
        if (fallbackOption?.available != true) {
            error("Required audio encoder $fallback is unavailable")
        }
        reasons += "Audio Auto fallback: $fallback"
        return fallback
    }

    private fun resolveContainer(
        info: FFProbeInspector.VideoInfo,
        preferences: OutputPreferences,
        catalog: List<EncoderOption>,
        videoEncoder: String?,
        audioEncoder: String?,
        reasons: MutableList<String>
    ): String {
        val videoFamily = videoEncoder?.let {
            EncoderCatalog.find(catalog, it)?.codecFamily
        } ?: EncoderCatalog.familyForInputCodec(info.videoCodec)

        val audioFamily = audioEncoder?.let {
            EncoderCatalog.find(catalog, it)?.codecFamily
        } ?: EncoderCatalog.familyForInputCodec(info.audioCodec)

        if (preferences.videoSectionMode == SectionMode.CUSTOM &&
            preferences.container != null
        ) {
            val requested = preferences.container.lowercase()

            if (requested == "ts" || requested == "m2ts") {
                error(
                    "MPEG-TS output is currently unsupported for transcoding. " +
                        "TS inputs are automatically processed to MP4. " +
                        "TS output preservation will be re-examined after the FFmpegKitNext upgrade."
                )
            }

            validateContainer(videoFamily, audioFamily, requested)
            reasons += "Custom container: $requested"
            return requested
        }

        val allowed = VIDEO_CONTAINER_COMPATIBILITY[videoFamily]
            ?: error("No container policy for video family $videoFamily")

        val inputFormats = info.containerFormat
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.toSet()
            ?: emptySet()

        val inputContainer = allowed.firstOrNull { it in inputFormats }

        val isMpegTsInput = "mpegts" in inputFormats

        val selected = when {
            // MPEG-TS output transcoding is currently unsupported on this
            // FFmpegKit build. TS inputs are deliberately planned as MP4
            // output. TS preservation requires the validated two-stage
            // MP4-to-TS remux path and will be re-examined after the
            // FFmpegKitNext upgrade.
            isMpegTsInput -> "mp4"
            inputContainer != null -> inputContainer
            videoFamily == "VP9" -> "webm"
            else -> "mp4"
        }

        validateContainer(videoFamily, audioFamily, selected)

        reasons += if (isMpegTsInput) {
            "Auto container: mp4 (MPEG-TS input; direct TS output transcoding is unsupported)"
        } else {
            "Auto container: $selected"
        }

        return selected
    }

    private fun validateContainer(
        videoFamily: String?,
        audioFamily: String?,
        container: String
    ) {
        if (videoFamily != null) {
            val allowed = VIDEO_CONTAINER_COMPATIBILITY[videoFamily]
            if (allowed != null && container !in allowed) {
                error(
                    "$videoFamily video is incompatible with .$container; " +
                        "allowed: ${allowed.joinToString(", ") { ".$it" }}"
                )
            }
        }
        if (audioFamily != null) {
            val allowed = AUDIO_CONTAINER_COMPATIBILITY[audioFamily]
            if (allowed != null && container !in allowed) {
                error(
                    "$audioFamily audio is incompatible with .$container; " +
                        "allowed: ${allowed.joinToString(", ") { ".$it" }}"
                )
            }
        }
    }

    private fun resolveQuality(
        mode: ExecutionMode,
        videoEncoder: String?,
        preferences: OutputPreferences,
        info: FFProbeInspector.VideoInfo,
        reasons: MutableList<String>
    ): Quality {
        if (mode == ExecutionMode.FAST) {
            return Quality(mode = QualityMode.BITRATE, bitrate = null)
        }

        val supportsCrf = videoEncoder in setOf(
            "libx264",
            "libx265"
        )
        val effectiveMode = if (supportsCrf) {
            preferences.qualityMode
        } else {
            QualityMode.BITRATE
        }

        return when (effectiveMode) {
            QualityMode.CRF -> {
                if (videoEncoder in setOf("h264_mediacodec", "hevc_mediacodec")) {
                    val cq = preferences.cq
                        ?: error("CQ value is required for $videoEncoder CQ mode")

                    reasons += "$videoEncoder CQ $cq"
                    Quality(
                        mode = QualityMode.CRF,
                        cq = cq
                    )
                } else {
                    val crf = preferences.crf ?: 23
                    if (crf !in 0..51) error("CRF must be between 0 and 51")

                    reasons += "$videoEncoder CRF $crf"
                    Quality(
                        mode = QualityMode.CRF,
                        crf = crf
                    )
                }
            }
            QualityMode.BITRATE -> {
                val bitrate = preferences.videoBitrate
                    ?: effectiveVideoBitrate(info)
                    ?: fallbackBitrate(info)

                val source = when {
                    preferences.videoBitrate != null -> "requested"
                    info.videoBitrate != null &&
                        info.videoBitrate >= minPlausibleVideoBitrate -> "input stream"
                    info.containerBitrate != null -> "container minus audio"
                    else -> "resolution fallback"
                }

                reasons += "$videoEncoder bitrate ${formatBitrate(bitrate)} ($source)"
                Quality(mode = QualityMode.BITRATE, bitrate = bitrate)
            }
        }
    }

    private data class Quality(
        val mode: QualityMode,
        val crf: Int? = null,
        val cq: Int? = null,
        val bitrate: Long? = null
    )

    private fun buildVideoArgs(
        mode: ExecutionMode,
        encoder: String?,
        quality: Quality,
        frameRate: String?,
        inputFrameRate: Float?,
        inputHeight: Int?,
        maxHeight: Int?
    ): List<String> {
        if (mode == ExecutionMode.FAST || encoder == null) return emptyList()

        val safeFrameRate = frameRate
            ?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }

        val args = mutableListOf("-c:v", encoder)
        when (quality.mode) {
            QualityMode.CRF -> {
                when (encoder) {
                    "libx264", "libx265" -> {
                        val crf = quality.crf ?: 23
                        args += listOf("-crf", crf.toString())
                    }
                    "h264_mediacodec", "hevc_mediacodec" -> {
                        val cq = quality.cq
                            ?: error("CQ value is required for $encoder CQ mode")

                        args += listOf(
                            "-bitrate_mode", "cq",
                            "-global_quality", cq.toString()
                        )

                        // MediaCodec derives i-frame-interval from GOP size / FPS.
                        // Ensure GOP size is at least the output FPS; otherwise the
                        // rounded interval can become zero and configure() can fail.
                        val gopSize = safeFrameRate
                            ?.toDoubleOrNull()
                            ?.roundToInt()
                            ?.coerceAtLeast(30)
                            ?: 120
                        args += listOf("-g", gopSize.toString())
                    }
                    else -> {
                        quality.bitrate?.let {
                            args += listOf("-b:v", "${it / 1000L}k")
                        }
                    }
                }
            }
            QualityMode.BITRATE -> {
                quality.bitrate?.let {
                    args += listOf("-b:v", "${it / 1000L}k")
                }
            }
        }
        // Preserve the source frame timing when the selected rate matches the
        // input. Forcing -r on an already-matching stream can normalize VFR or
        // fractional-rate MPEG-TS input and has caused MediaCodec video-duration
        // loss on long files.
        val shouldForceFrameRate = safeFrameRate != null && run {
            val requested = safeFrameRate.toDoubleOrNull()
            val source = inputFrameRate?.toDouble()

            requested == null ||
                source == null ||
                kotlin.math.abs(requested - source) > 0.01
        }

        if (shouldForceFrameRate) {
            args += listOf("-r", safeFrameRate)
        }

        // Downscale only. Never upscale an input that is already smaller.
        if (maxHeight != null && inputHeight != null && inputHeight > maxHeight) {
            args += listOf("-vf", "scale=-2:$maxHeight")
        }
        return args
    }

    private fun fallbackVideoArgs(
        mode: ExecutionMode,
        encoder: String?,
        quality: Quality
    ): List<String> {
        if (mode != ExecutionMode.HW_ACCURATE || encoder == null) {
            return listOf("-c:v", "libx264", "-crf", "23")
        }
        val bitrate = quality.bitrate ?: 4_000_000L
        return listOf(
            "-c:v", encoder,
            "-b:v", "${bitrate / 1000L}k",
            "-pix_fmt", "yuv420p"
        )
    }

    private const val minPlausibleAudioBitrate = 32_000L
    private const val fallbackAudioBitrate = 128_000L

    private fun effectiveAudioBitrate(info: FFProbeInspector.VideoInfo): Long {
        val parsed = info.audioBitrate
        return if (parsed != null && parsed >= minPlausibleAudioBitrate) {
            parsed
        } else {
            DiagLog.append(
                "AudioPolicy",
                "Source audio bitrate ${parsed ?: "missing"} is missing or below " +
                    "${minPlausibleAudioBitrate / 1000}k; using ${fallbackAudioBitrate / 1000}k"
            )
            fallbackAudioBitrate
        }
    }

    private const val minPlausibleVideoBitrate = 64_000L

    private fun effectiveVideoBitrate(info: FFProbeInspector.VideoInfo): Long? {
        val streamBitrate = info.videoBitrate
        if (streamBitrate != null && streamBitrate >= minPlausibleVideoBitrate) {
            return streamBitrate
        }

        val audioBitrate = effectiveAudioBitrate(info)
        val inferred = info.containerBitrate?.minus(audioBitrate)
        if (inferred != null && inferred >= minPlausibleVideoBitrate) {
            DiagLog.append(
                "VideoPolicy",
                "Stream video bitrate ${streamBitrate ?: "missing"} is missing or implausible; " +
                    "inferred $inferred from container ${info.containerBitrate} minus audio $audioBitrate"
            )
            return inferred
        }

        DiagLog.append(
            "VideoPolicy",
            "Unable to infer plausible video bitrate; stream=${streamBitrate ?: "missing"}, " +
                "container=${info.containerBitrate ?: "missing"}, inferred=${inferred ?: "missing"}"
        )
        return null
    }




    private fun buildAudioArgs(
        encoder: String?,
        info: FFProbeInspector.VideoInfo,
        sampleRate: Int?,
        requestedBitrate: Long? = null
    ): List<String> {
        if (encoder == null) return emptyList()
        val args = mutableListOf("-c:a", encoder)
        val bitrate = requestedBitrate?.takeIf { it >= minPlausibleAudioBitrate }
            ?: effectiveAudioBitrate(info)

        DiagLog.append(
            "AudioPolicy",
            "Audio bitrate: requested=${requestedBitrate ?: "none"}, " +
                "source=${info.audioBitrate ?: "missing"}, effective=${bitrate / 1000L}k"
        )

        if (encoder in setOf("aac", "libopus", "libmp3lame", "libvorbis")) {
            args += listOf("-b:a", "${bitrate / 1000L}k")
        }
        sampleRate?.let { args += listOf("-ar", it.toString()) }
        return args
    }

    private fun fallbackBitrate(info: FFProbeInspector.VideoInfo): Long {
        val pixels = info.pixels ?: 0
        val kbps = when {
            pixels >= 1920 * 1080 -> 1500
            pixels >= 1280 * 720 -> 1000
            pixels >= 854 * 480 -> 600
            else -> 400
        }
        return kbps * 1000L
    }

    private fun complexityFor(
        info: FFProbeInspector.VideoInfo,
        mode: ExecutionMode
    ): ComplexityTier {
        val pixels = info.pixels ?: 0
        val fps = info.fps ?: 30f
        return when {
            mode == ExecutionMode.FAST -> ComplexityTier.LOW
            pixels >= 1920 * 1080 && fps >= 50f -> ComplexityTier.HIGH
            pixels >= 1920 * 1080 -> ComplexityTier.MEDIUM
            else -> ComplexityTier.LOW
        }
    }

    private fun audioContainerFor(codec: String?): String =
        when (codec?.lowercase()) {
            "aac" -> "m4a"
            "mp3" -> "mp3"
            "opus" -> "opus"
            "vorbis" -> "ogg"
            "flac" -> "flac"
            else -> "m4a"
        }

    private fun videoContainerFor(codec: String?): String =
        when (codec?.lowercase()) {
            "vp8", "vp9" -> "webm"
            "h264", "hevc", "h265", "av1", "mpeg4" -> "mp4"
            else -> "mp4"
        }

    private fun muxerForContainer(container: String): String? =
        when (container) {
            "mp4" -> "mp4"
            "webm" -> "webm"
            "mkv" -> "matroska"
            "mov" -> "mov"
            "ts", "m2ts" -> "mpegts"
            "avi" -> "avi"
            "ogg", "oga", "opus" -> "ogg"
            "mp3" -> "mp3"
            "flac" -> "flac"
            "wav" -> "wav"
            else -> null
        }

    private fun formatBitrate(bitsPerSecond: Long): String =
        "${bitsPerSecond / 1000L}k"


    fun buildSizeAdvisory(
        inputSizeBytes: Long,
        outputSizeBytes: Long,
        inputDurationSec: Double?,
        outputDurationSec: Double?,
        requestedVideoBitrateBps: Long?,
        actualVideoBitrateBps: Long?,
        executionMode: ExecutionMode
    ): String? {
        if (executionMode == ExecutionMode.FAST) return null
        if (outputSizeBytes <= inputSizeBytes) return null

        val ratio = outputSizeBytes.toDouble() / inputSizeBytes.toDouble()
        if (ratio < 1.10) return null

        val removedSeconds = inputDurationSec?.let { inputDuration ->
            outputDurationSec?.let { outputDuration ->
                inputDuration - outputDuration
            }
        }

        val requested = requestedVideoBitrateBps?.let { bitrate ->
            "${"%.0f".format(bitrate / 1000.0)} kb/s"
        }

        val actual = actualVideoBitrateBps?.let { bitrate ->
            "${"%.0f".format(bitrate / 1000.0)} kb/s"
        }

        val bitrateClause = if (requested != null && actual != null) {
            "Requested video target: $requested; actual output video: $actual."
        } else {
            "The selected encoder could not preserve the source video bitrate exactly."
        }

        val durationClause = removedSeconds?.takeIf { it > 0.5 }?.let { seconds ->
            "Removed ${"%.1f".format(seconds)} seconds. "
        } ?: ""

        return """
            ⚠️ Output is ${"%.1f".format(ratio)}× the original size.
            $durationClause$bitrateClause
            The source’s unusually low video bitrate is difficult for this
            device’s hardware encoder to preserve. Try SW Accurate mode, or
            Fast mode if frame-accurate re-encoding is not required.
        """.trimIndent()
    }
}
