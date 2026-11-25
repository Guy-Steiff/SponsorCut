//java/com/sponsorcut/TranscodePolicy.kt

package com.sponsorcut

import com.sponsorcut.core.model.ExecutionMode
import com.sponsorcut.core.model.JobKind
import com.sponsorcut.core.model.StreamSelection
import kotlin.math.roundToInt

object TranscodePolicy {

    class PolicyResolutionException(message: String) : IllegalStateException(message)

    enum class ComplexityTier { LOW, MEDIUM, HIGH }

    enum class AudioBitrateProvenance {
        NONE,
        STREAM_COPY,
        EXPLICIT,
        AUTO_SOURCE,
        POLICY_FALLBACK,
        EXPLICIT_BELOW_MINIMUM_SOURCE,
        EXPLICIT_BELOW_MINIMUM_FALLBACK
    }

    enum class VideoBitrateProvenance {
        NONE,
        STREAM_COPY,
        EXPLICIT,
        INPUT_STREAM,
        CONTAINER_MINUS_AUDIO,
        RESOLUTION_FALLBACK
    }

    enum class FrameRateProvenance {
        NONE,
        INPUT,
        INPUT_VARIABLE,
        EXPLICIT
    }

    data class OutputResolution(
        val requestedVideoBitrateBps: Long?,
        val videoBitrateBps: Long?,
        val videoBitrateProvenance: VideoBitrateProvenance,
        val requestedAudioBitrateBps: Long?,
        val audioBitrateBps: Long?,
        val audioBitrateProvenance: AudioBitrateProvenance,
        val requestedFrameRate: String?,
        val inputFrameRate: String?,
        val effectiveFrameRate: String?,
        val frameRateDisplay: String?,
        val inputFrameRateDisplay: String?,
        val frameRateProvenance: FrameRateProvenance,
        val forceFrameRate: Boolean
    )

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
        val outputResolution: OutputResolution,
        val executionMode: ExecutionMode,
        val videoEncoder: String? = null,
        val audioEncoder: String? = null,
        val useSlowSeek: Boolean = false,
        val singlePassFilter: Boolean = false,
        val dropVideo: Boolean = false,
        val dropAudio: Boolean = false,
        val jobKind: JobKind = JobKind.REMOVE_SEGMENTS,
        val removeSegments: Boolean = true,
        val streamSelection: StreamSelection = StreamSelection.BOTH,
        val audioBitrateBps: Long? = null,
        val requestedAudioBitrateBps: Long? = null,
        val audioBitrateProvenance: AudioBitrateProvenance =
            AudioBitrateProvenance.NONE,
        val videoBitrateBps: Long? = null,
        val requestedVideoBitrateBps: Long? = null,
        val videoBitrateProvenance: VideoBitrateProvenance =
            VideoBitrateProvenance.NONE
    )

    data class UiPreview(
        val outputResolution: OutputResolution?,
        val videoEncoder: String?,
        val audioEncoder: String?,
        val outputExtension: String?,
        val canCopyVideo: Boolean,
        val canCopyAudio: Boolean,
        val unsupportedMessage: String? = null,
        val failureStackTrace: String? = null
    )

    private data class AudioEncoding(
        val args: List<String>,
        val bitrateBps: Long?,
        val requestedBitrateBps: Long?,
        val provenance: AudioBitrateProvenance
    )

    private data class AudioBitrateResolution(
        val bitrateBps: Long,
        val provenance: AudioBitrateProvenance
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

    private val AUTO_VIDEO_FALLBACK_FAMILIES = listOf(
        "H.264",
        "VP9",
        "AV1",
        "MPEG-4 Part 2"
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
        val compatibilityContainer = resolveContainer(
            info,
            preferences,
            catalog,
            videoEncoder,
            null,
            mutableListOf(),
            validateAudioCompatibility = false
        )
        val audioEncoder = resolveAudioEncoder(
            mode = mode,
            preferences = preferences,
            catalog = catalog,
            inputFamily = inputAudioFamily,
            videoEncoder = videoEncoder,
            container = compatibilityContainer,
            reasons = reasons
        )
        val container = resolveContainer(
            info, preferences, catalog, videoEncoder, audioEncoder, reasons
        )

        val canCopyVideo = mode == ExecutionMode.FAST && videoEncoder == null
        val canCopyAudio = mode == ExecutionMode.FAST && audioEncoder == null

        val quality = resolveQuality(mode, videoEncoder, preferences, info, reasons)
        val frameRate = resolveFrameRate(preferences.videoFrameRate, info)

        val videoArgs = buildVideoArgs(
            mode, videoEncoder, quality, frameRate,
            info.height, preferences.videoMaxHeight
        ).toMutableList()

        val audioEncoding = buildAudioArgs(
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

        val outputResolution = outputResolution(
            info = info,
            preferences = preferences,
            quality = quality,
            audioEncoding = audioEncoding,
            frameRate = frameRate,
            copiedVideo = canCopyVideo,
            copiedAudio = canCopyAudio
        )

        return ProcessingPlan(
            canCopyVideo = canCopyVideo,
            canCopyAudio = canCopyAudio,
            videoEncoderArgs = videoArgs,
            fallbackVideoEncoderArgs = fallbackVideoArgs(mode, videoEncoder, quality),
            hwDecoderArgs = emptyList(),
            audioEncoderArgs = audioEncoding.args,
            fallbackAudioEncoderArgs = listOf("-c:a", "aac", "-b:a", "128k"),
            outputExtension = container,
            outputMuxer = outputMuxer,
            complexity = complexityFor(info, mode),
            rationale = reasons.joinToString("; ").ifBlank { "input-matched processing" },
            outputResolution = outputResolution,
            executionMode = mode,
            videoEncoder = videoEncoder,
            audioEncoder = audioEncoder,
            useSlowSeek = info.isTsEncapsulated,
            singlePassFilter = singlePass,
            removeSegments = removeSegments,
            streamSelection = StreamSelection.BOTH,
            audioBitrateBps = audioEncoding.bitrateBps,
            requestedAudioBitrateBps = audioEncoding.requestedBitrateBps,
            audioBitrateProvenance = audioEncoding.provenance,
            videoBitrateBps = outputResolution.videoBitrateBps,
            requestedVideoBitrateBps = preferences.videoBitrate,
            videoBitrateProvenance = outputResolution.videoBitrateProvenance
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
                mode = mode,
                preferences = preferences,
                catalog = catalog,
                inputFamily = inputFamily,
                videoEncoder = null,
                reasons = reasons
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

            // TEMPORARY DIAGNOSTIC: identify why Fast audio-only extraction
            // can produce an MP4 extension despite the UI preview showing M4A.
            DiagLog.append(
                "AudioExtractionPolicy",
                "mode=$mode " +
                        "canCopy=$canCopy " +
                        "inputAudioCodec=${info.audioCodec} " +
                        "resolvedEncoder=$encoder " +
                        "audioContainer=${preferences.audioContainer} " +
                        "videoContainer=${preferences.container} " +
                        "audioSectionMode=${preferences.audioSectionMode} " +
                        "resolvedContainer=$container"
            )

            val audioEncoding = buildAudioArgs(
                encoder,
                info,
                preferences.audioSampleRate,
                preferences.audioBitrate
            )
            val frameRate = resolveFrameRate(preferences.videoFrameRate, info)
            val outputResolution = outputResolution(
                info = info,
                preferences = preferences,
                quality = Quality(QualityMode.BITRATE),
                audioEncoding = audioEncoding,
                frameRate = frameRate,
                copiedVideo = false,
                copiedAudio = canCopy
            )

            // ADTS is a raw AAC stream. Use Android's canonical `.aac`
            // filename extension; FFmpeg selects its ADTS muxer from it.
            val outputExtension = if (container.equals("adts", ignoreCase = true)) {
                "aac"
            } else {
                container
            }

            return ProcessingPlan(
                canCopyVideo = false,
                canCopyAudio = canCopy,
                videoEncoderArgs = empty,
                fallbackVideoEncoderArgs = empty,
                hwDecoderArgs = empty,
                audioEncoderArgs = audioEncoding.args,
                fallbackAudioEncoderArgs = listOf("-c:a", "aac", "-b:a", "128k"),
                outputExtension = outputExtension,
                outputMuxer = muxerForContainer(container),
                complexity = ComplexityTier.LOW,
                rationale = reasons.joinToString("; ").ifBlank { "audio extraction" },
                outputResolution = outputResolution,
                executionMode = mode,
            videoEncoder = null,
            audioEncoder = encoder,
            useSlowSeek = info.isTsEncapsulated,
                singlePassFilter = removeSegments && !canCopy,
                dropVideo = true,
                dropAudio = false,
                jobKind = JobKind.EXTRACT_AUDIO,
                removeSegments = removeSegments,
                streamSelection = streamSelection,
                audioBitrateBps = audioEncoding.bitrateBps,
                requestedAudioBitrateBps = audioEncoding.requestedBitrateBps,
                audioBitrateProvenance = audioEncoding.provenance,
                videoBitrateBps = null,
                requestedVideoBitrateBps = preferences.videoBitrate,
                videoBitrateProvenance = VideoBitrateProvenance.NONE
            )
        }

        val inputFamily = EncoderCatalog.familyForInputCodec(info.videoCodec)
        val encoder = resolveVideoEncoder(
            info, mode, preferences, catalog, inputFamily, reasons
        )
        val canCopy = mode == ExecutionMode.FAST && encoder == null
        val quality = resolveQuality(mode, encoder, preferences, info, reasons)
        val frameRate = resolveFrameRate(preferences.videoFrameRate, info)
        val outputResolution = outputResolution(
            info = info,
            preferences = preferences,
            quality = quality,
            audioEncoding = AudioEncoding(
                empty,
                null,
                preferences.audioBitrate,
                AudioBitrateProvenance.NONE
            ),
            frameRate = frameRate,
            copiedVideo = canCopy,
            copiedAudio = false
        )
        val container = preferences.container.takeIf {
            preferences.videoSectionMode == SectionMode.CUSTOM
        } ?: videoContainerFor(encoder ?: info.videoCodec)

        return ProcessingPlan(
            canCopyVideo = canCopy,
            canCopyAudio = false,
            videoEncoderArgs = buildVideoArgs(
                mode, encoder, quality, frameRate,
                info.height, preferences.videoMaxHeight
            ),
            fallbackVideoEncoderArgs = fallbackVideoArgs(mode, encoder, quality),
            hwDecoderArgs = emptyList(),
            audioEncoderArgs = empty,
            fallbackAudioEncoderArgs = empty,
            outputExtension = container,
            outputMuxer = muxerForContainer(container),
            complexity = complexityFor(info, mode),
            rationale = reasons.joinToString("; ").ifBlank { "video extraction" },
            outputResolution = outputResolution,
            executionMode = mode,
            videoEncoder = encoder,
            audioEncoder = null,
            useSlowSeek = info.isTsEncapsulated,
            singlePassFilter = removeSegments && !canCopy,
            dropVideo = false,
            dropAudio = true,
            jobKind = JobKind.EXTRACT_VIDEO,
            removeSegments = removeSegments,
            streamSelection = streamSelection,
            videoBitrateBps = outputResolution.videoBitrateBps,
            requestedVideoBitrateBps = preferences.videoBitrate,
            videoBitrateProvenance = outputResolution.videoBitrateProvenance
        )
    }

    fun resolveForUi(
        info: FFProbeInspector.VideoInfo,
        frameAccurate: Boolean,
        hwAccurate: Boolean = false,
        removeSegments: Boolean = true,
        streamSelection: StreamSelection = StreamSelection.BOTH,
        preferences: OutputPreferences = OutputPreferences(),
        catalog: List<EncoderOption> = EncoderCatalog.discover()
    ): UiPreview {
        return try {
            val processingPlan = plan(
                info = info,
                frameAccurate = frameAccurate,
                hwAccurate = hwAccurate,
                removeSegments = removeSegments,
                streamSelection = streamSelection,
                preferences = preferences,
                catalog = catalog
            )
            UiPreview(
                outputResolution = processingPlan.outputResolution,
                videoEncoder = processingPlan.videoEncoder,
                audioEncoder = processingPlan.audioEncoder,
                outputExtension = processingPlan.outputExtension,
                canCopyVideo = processingPlan.canCopyVideo,
                canCopyAudio = processingPlan.canCopyAudio
            )
        } catch (e: PolicyResolutionException) {
            UiPreview(
                outputResolution = null,
                videoEncoder = null,
                audioEncoder = null,
                outputExtension = null,
                canCopyVideo = false,
                canCopyAudio = false,
                unsupportedMessage = e.message ?: "Output settings are not supported.",
                failureStackTrace = e.stackTraceToString()
            )
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
                ?: throw PolicyResolutionException("Video is Custom but no video codec was selected")
            val option = EncoderCatalog.find(catalog, requested)
                ?: throw PolicyResolutionException("Unknown video encoder: $requested")
            if (!option.available) throw PolicyResolutionException("Video encoder $requested is unavailable")
            if (mode == ExecutionMode.SW_ACCURATE && option.hardware) {
                throw PolicyResolutionException("$requested is a hardware encoder; select HW mode")
            }
            if (mode == ExecutionMode.HW_ACCURATE && !option.hardware) {
                throw PolicyResolutionException("$requested is a software encoder; select SW mode")
            }
            reasons += "Custom video encoder: $requested"
            return requested
        }

        val availableOptions = EncoderCatalog.videoOptions(catalog, mode)
            .filter { it.available }
        val inputMatch = inputFamily?.let { family ->
            availableOptions.firstOrNull { it.codecFamily == family }
        }
        if (inputMatch != null) {
            reasons += "Auto matches input family $inputFamily using ${inputMatch.name}"
            return inputMatch.name
        }

        for (family in AUTO_VIDEO_FALLBACK_FAMILIES) {
            val fallback = availableOptions.firstOrNull { it.codecFamily == family }
            if (fallback != null) {
                reasons += "Auto fallback: ${inputFamily ?: info.videoCodec} -> " +
                    "$family using ${fallback.name}"
                return fallback.name
            }
        }

        val fallback = availableOptions.firstOrNull()
            ?: throw PolicyResolutionException(
                "No available ${mode.name.lowercase()} video encoder matches the Auto fallback hierarchy"
            )
        reasons += "Auto fallback: no preferred family encoder available; using ${fallback.name}"
        return fallback.name
    }

    private fun resolveAudioEncoder(
        mode: ExecutionMode,
        preferences: OutputPreferences,
        catalog: List<EncoderOption>,
        inputFamily: String?,
        videoEncoder: String?,
        reasons: MutableList<String>,
        container: String? = null
    ): String? {
        if (mode == ExecutionMode.FAST) {
            reasons += "Fast audio stream copy"
            return null
        }

        if (preferences.audioSectionMode == SectionMode.CUSTOM) {
            val requested = preferences.audioCodec
                ?: throw PolicyResolutionException("Audio is Custom but no audio codec was selected")
            val option = EncoderCatalog.find(catalog, requested)
                ?: throw PolicyResolutionException("Unknown audio encoder: $requested")
            if (!option.available) throw PolicyResolutionException("Audio encoder $requested is unavailable")
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

        val inputEncoderOption = EncoderCatalog.find(catalog, inputEncoder)
        val inputAudioCompatible = inputEncoderOption?.let { option ->
            container == null ||
                AUDIO_CONTAINER_COMPATIBILITY[option.codecFamily]?.contains(container) != false
        } == true
        if (inputEncoder != null && inputEncoderOption?.available == true && inputAudioCompatible) {
            reasons += "Audio Auto matches input family $inputFamily using $inputEncoder"
            return inputEncoder
        }

        val videoFamily = videoEncoder?.let {
            EncoderCatalog.find(catalog, it)?.codecFamily
        }
        val fallback = DEFAULT_AUDIO_BY_FAMILY[videoFamily] ?: "aac"
        val fallbackOption = EncoderCatalog.find(catalog, fallback)
        if (fallbackOption?.available != true) {
            throw PolicyResolutionException("Required audio encoder $fallback is unavailable")
        }
        reasons += if (inputEncoder != null && !inputAudioCompatible && container != null) {
            "Audio Auto fallback: $inputEncoder is incompatible with .$container; using $fallback"
        } else {
            "Audio Auto fallback: $fallback"
        }
        return fallback
    }

    private fun resolveContainer(
        info: FFProbeInspector.VideoInfo,
        preferences: OutputPreferences,
        catalog: List<EncoderOption>,
        videoEncoder: String?,
        audioEncoder: String?,
        reasons: MutableList<String>,
        validateAudioCompatibility: Boolean = true
    ): String {
        val videoFamily = videoEncoder?.let {
            EncoderCatalog.find(catalog, it)?.codecFamily
        } ?: EncoderCatalog.familyForInputCodec(info.videoCodec)

        val audioFamily = if (!validateAudioCompatibility) {
            null
        } else {
            audioEncoder?.let {
                EncoderCatalog.find(catalog, it)?.codecFamily
            } ?: EncoderCatalog.familyForInputCodec(info.audioCodec)
        }

        if (preferences.videoSectionMode == SectionMode.CUSTOM &&
            preferences.container != null
        ) {
            val requested = preferences.container.lowercase()

            if (requested == "ts" || requested == "m2ts") {
                throw PolicyResolutionException(
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
            ?: throw PolicyResolutionException("No container policy for video family $videoFamily")

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
                throw PolicyResolutionException(
                    "$videoFamily video is incompatible with .$container; " +
                        "allowed: ${allowed.joinToString(", ") { ".$it" }}"
                )
            }
        }
        if (audioFamily != null) {
            val allowed = AUDIO_CONTAINER_COMPATIBILITY[audioFamily]
            if (allowed != null && container !in allowed) {
                throw PolicyResolutionException(
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
            return Quality(
                mode = QualityMode.BITRATE,
                bitrate = null,
                bitrateProvenance = VideoBitrateProvenance.STREAM_COPY
            )
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
                    ?: throw PolicyResolutionException("CQ value is required for $videoEncoder CQ mode")

                    reasons += "$videoEncoder CQ $cq"
                    Quality(
                        mode = QualityMode.CRF,
                        cq = cq
                    )
                } else {
                    val crf = preferences.crf ?: 23
                    if (crf !in 0..51) throw PolicyResolutionException("CRF must be between 0 and 51")

                    reasons += "$videoEncoder CRF $crf"
                    Quality(
                        mode = QualityMode.CRF,
                        crf = crf
                    )
                }
            }
            QualityMode.BITRATE -> {
                val resolved = when {
                    preferences.videoBitrate != null ->
                        VideoBitrateResolution(
                            preferences.videoBitrate,
                            VideoBitrateProvenance.EXPLICIT
                        )
                    else -> effectiveVideoBitrate(info) ?: VideoBitrateResolution(
                        fallbackBitrate(info),
                        VideoBitrateProvenance.RESOLUTION_FALLBACK
                    )
                }

                reasons += "$videoEncoder bitrate ${formatBitrate(resolved.bitrateBps)} " +
                    "(${resolved.provenance})"
                Quality(
                    mode = QualityMode.BITRATE,
                    bitrate = resolved.bitrateBps,
                    bitrateProvenance = resolved.provenance
                )
            }
        }
    }

    private data class Quality(
        val mode: QualityMode,
        val crf: Int? = null,
        val cq: Int? = null,
        val bitrate: Long? = null,
        val bitrateProvenance: VideoBitrateProvenance =
            VideoBitrateProvenance.NONE
    )

    private data class FrameRateResolution(
        val requested: String?,
        val inputDisplay: String?,
        val effective: String?,
        val display: String?,
        val provenance: FrameRateProvenance,
        val force: Boolean
    )

    private data class VideoBitrateResolution(
        val bitrateBps: Long,
        val provenance: VideoBitrateProvenance
    )

    private fun buildVideoArgs(
        mode: ExecutionMode,
        encoder: String?,
        quality: Quality,
        frameRate: FrameRateResolution,
        inputHeight: Int?,
        maxHeight: Int?
    ): List<String> {
        if (mode == ExecutionMode.FAST || encoder == null) return emptyList()

        val safeFrameRate = frameRate.requested
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
                            ?: throw PolicyResolutionException("CQ value is required for $encoder CQ mode")

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
                            args += listOf("-b:v", it.toString())
                        }
                    }
                }
            }
            QualityMode.BITRATE -> {
                quality.bitrate?.let {
                    args += listOf("-b:v", it.toString())
                }
            }
        }
        // Preserve the source frame timing when the selected rate matches the
        // input. Forcing -r on an already-matching stream can normalize VFR or
        // fractional-rate MPEG-TS input and has caused MediaCodec video-duration
        // loss on long files.
        if (frameRate.force && safeFrameRate != null) {
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
            "-b:v", bitrate.toString(),
            "-pix_fmt", "yuv420p"
        )
    }

    private const val MIN_PLAUSIBLE_AUDIO_BITRATE = 32_000L
    private const val FALLBACK_AUDIO_BITRATE = 128_000L

    private fun effectiveAudioBitrate(info: FFProbeInspector.VideoInfo): Long {
        return resolveAudioBitrate(info, requestedBitrate = null).bitrateBps
    }

    private const val MIN_PLAUSIBLE_VIDEO_BITRATE = 64_000L

    private fun effectiveVideoBitrate(
        info: FFProbeInspector.VideoInfo
    ): VideoBitrateResolution? {
        val streamBitrate = info.videoBitrate
        if (streamBitrate != null && streamBitrate >= MIN_PLAUSIBLE_VIDEO_BITRATE) {
            return VideoBitrateResolution(
                streamBitrate,
                VideoBitrateProvenance.INPUT_STREAM
            )
        }

        val audioBitrate = effectiveAudioBitrate(info)
        val inferred = info.containerBitrate?.minus(audioBitrate)
        if (inferred != null && inferred >= MIN_PLAUSIBLE_VIDEO_BITRATE) {
            DiagLog.append(
                "VideoPolicy",
                "Stream video bitrate ${streamBitrate ?: "missing"} is missing or implausible; " +
                    "inferred $inferred from container ${info.containerBitrate} minus audio $audioBitrate"
            )
            return VideoBitrateResolution(
                inferred,
                VideoBitrateProvenance.CONTAINER_MINUS_AUDIO
            )
        }

        DiagLog.append(
            "VideoPolicy",
            "Unable to infer plausible video bitrate; stream=${streamBitrate ?: "missing"}, " +
                "container=${info.containerBitrate ?: "missing"}, inferred=${inferred ?: "missing"}"
        )
        return null
    }

    private fun resolveFrameRate(
        requested: String?,
        info: FFProbeInspector.VideoInfo
    ): FrameRateResolution {
        val sourceRate = info.preferredFrameRate?.value ?: info.fps?.toDouble()
        val sourceValue = info.preferredFrameRate?.decimal() ?: info.fps?.let {
            java.math.BigDecimal.valueOf(it.toDouble()).stripTrailingZeros().toPlainString()
        }
        val sourceDisplay = info.preferredFrameRate?.decimal(3) ?: info.fps?.let {
            "%.2f".format(it)
        }
        val nominal = info.nominalFrameRate
        val average = info.averageFrameRate
        val isVariable = nominal != null && average != null &&
            kotlin.math.abs(nominal.value - average.value) > 0.000001
        val inputDisplay = when {
            isVariable ->
                "avg ${average.decimal(3)} fps; nominal ${nominal.decimal(3)} fps"
            info.preferredFrameRate != null ->
                "${info.preferredFrameRate.decimal(3)} fps"
            sourceDisplay != null -> "$sourceDisplay fps"
            else -> null
        }

        val safeRequest = requested?.takeUnless {
            it.isBlank() || it.equals("null", ignoreCase = true)
        }
        val shouldForce = safeRequest != null && run {
            val requestedValue = safeRequest.toDoubleOrNull()
            requestedValue == null || sourceRate == null ||
                kotlin.math.abs(requestedValue - sourceRate) > 0.01
        }

        return FrameRateResolution(
            requested = safeRequest,
            inputDisplay = inputDisplay,
            effective = safeRequest ?: sourceValue,
            display = if (safeRequest != null) "$safeRequest fps" else inputDisplay,
            provenance = when {
                safeRequest != null -> FrameRateProvenance.EXPLICIT
                isVariable -> FrameRateProvenance.INPUT_VARIABLE
                sourceValue != null -> FrameRateProvenance.INPUT
                else -> FrameRateProvenance.NONE
            },
            force = shouldForce
        )
    }

    private fun outputResolution(
        info: FFProbeInspector.VideoInfo,
        preferences: OutputPreferences,
        quality: Quality,
        audioEncoding: AudioEncoding,
        frameRate: FrameRateResolution,
        copiedVideo: Boolean,
        copiedAudio: Boolean
    ): OutputResolution {
        val videoBitrate = quality.bitrate ?: if (copiedVideo) {
            info.videoBitrate
        } else {
            null
        }
        val videoProvenance = when {
            quality.bitrate != null -> quality.bitrateProvenance
            copiedVideo && videoBitrate != null -> VideoBitrateProvenance.STREAM_COPY
            else -> VideoBitrateProvenance.NONE
        }
        val audioBitrate = audioEncoding.bitrateBps ?: if (copiedAudio) {
            info.audioBitrate
        } else {
            null
        }
        val audioProvenance = when {
            audioEncoding.bitrateBps != null -> audioEncoding.provenance
            copiedAudio && audioBitrate != null -> AudioBitrateProvenance.STREAM_COPY
            else -> audioEncoding.provenance
        }

        return OutputResolution(
            requestedVideoBitrateBps = preferences.videoBitrate,
            videoBitrateBps = videoBitrate,
            videoBitrateProvenance = videoProvenance,
            requestedAudioBitrateBps = audioEncoding.requestedBitrateBps,
            audioBitrateBps = audioBitrate,
            audioBitrateProvenance = audioProvenance,
            requestedFrameRate = frameRate.requested,
            inputFrameRate = info.preferredFrameRate?.decimal()
                ?: info.fps?.let {
                    java.math.BigDecimal.valueOf(it.toDouble()).stripTrailingZeros().toPlainString()
                },
            effectiveFrameRate = frameRate.effective,
            frameRateDisplay = frameRate.display,
            inputFrameRateDisplay = frameRate.inputDisplay,
            frameRateProvenance = frameRate.provenance,
            forceFrameRate = frameRate.force
        )
    }




    private fun buildAudioArgs(
        encoder: String?,
        info: FFProbeInspector.VideoInfo,
        sampleRate: Int?,
        requestedBitrate: Long? = null
    ): AudioEncoding {
        if (encoder == null) {
            return AudioEncoding(
                args = emptyList(),
                bitrateBps = null,
                requestedBitrateBps = requestedBitrate,
                provenance = AudioBitrateProvenance.NONE
            )
        }

        val args = mutableListOf("-c:a", encoder)
        val bitrate = if (encoder in setOf("aac", "libopus", "libmp3lame", "libvorbis")) {
            resolveAudioBitrate(info, requestedBitrate)
        } else {
            null
        }

        if (bitrate != null) {
            DiagLog.append(
                "AudioPolicy",
                "Audio bitrate: requested=${requestedBitrate ?: "Auto"}, " +
                    "source=${info.audioBitrate ?: "missing"}bps, " +
                    "effective=${bitrate.bitrateBps}bps, provenance=${bitrate.provenance}"
            )
            args += listOf("-b:a", bitrate.bitrateBps.toString())
        }
        sampleRate?.let { args += listOf("-ar", it.toString()) }
        return AudioEncoding(
            args = args,
            bitrateBps = bitrate?.bitrateBps,
            requestedBitrateBps = requestedBitrate,
            provenance = bitrate?.provenance ?: AudioBitrateProvenance.NONE
        )
    }

    private fun resolveAudioBitrate(
        info: FFProbeInspector.VideoInfo,
        requestedBitrate: Long?
    ): AudioBitrateResolution {
        if (requestedBitrate != null && requestedBitrate >= MIN_PLAUSIBLE_AUDIO_BITRATE) {
            return AudioBitrateResolution(
                requestedBitrate,
                AudioBitrateProvenance.EXPLICIT
            )
        }

        val explicitBelowMinimum = requestedBitrate != null
        if (explicitBelowMinimum) {
            DiagLog.append(
                "AudioPolicy",
                "Requested audio bitrate $requestedBitrate bps is below " +
                    "$MIN_PLAUSIBLE_AUDIO_BITRATE bps; using source policy"
            )
        }

        val sourceBitrate = info.audioBitrate
        if (sourceBitrate != null && sourceBitrate >= MIN_PLAUSIBLE_AUDIO_BITRATE) {
            return AudioBitrateResolution(
                sourceBitrate,
                if (explicitBelowMinimum) {
                    AudioBitrateProvenance.EXPLICIT_BELOW_MINIMUM_SOURCE
                } else {
                    AudioBitrateProvenance.AUTO_SOURCE
                }
            )
        }

        DiagLog.append(
            "AudioPolicy",
            "Source audio bitrate ${sourceBitrate ?: "missing"} is missing or below " +
                "${MIN_PLAUSIBLE_AUDIO_BITRATE}bps; using ${FALLBACK_AUDIO_BITRATE}bps"
        )
        return AudioBitrateResolution(
            FALLBACK_AUDIO_BITRATE,
            if (explicitBelowMinimum) {
                AudioBitrateProvenance.EXPLICIT_BELOW_MINIMUM_FALLBACK
            } else {
                AudioBitrateProvenance.POLICY_FALLBACK
            }
        )
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
