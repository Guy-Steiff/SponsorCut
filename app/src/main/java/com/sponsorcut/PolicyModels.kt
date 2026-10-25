// app/src/main/java/com/sponsorcut/PolicyModels.kt

package com.sponsorcut

enum class SectionMode {
    AUTO,
    CUSTOM
}

enum class QualityMode {
    CRF,
    BITRATE
}

data class EncoderOption(
    val name: String,
    val label: String,
    val codecFamily: String,
    val mediaType: String,
    val hardware: Boolean,
    val available: Boolean
) {
    val isVideo: Boolean get() = mediaType == "video"
    val isAudio: Boolean get() = mediaType == "audio"
}

data class OutputPreferences(
    val videoSectionMode: SectionMode = SectionMode.AUTO,
    val audioSectionMode: SectionMode = SectionMode.AUTO,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val container: String? = null,
    val audioContainer: String? = null,
    val qualityMode: QualityMode = QualityMode.BITRATE,
    val crf: Int? = null,
    val cq: Int? = null,
    val videoBitrate: Long? = null,
    val preset: String? = null,
    val videoFrameRate: String? = null,
    val audioSampleRate: Int? = null,
    val audioBitrate: Long? = null,
    val videoMaxHeight: Int? = null
) {
    fun toJson(): String {
        fun q(value: String?): String =
            value?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"

        return buildString {
            append("{")
            append("\"videoSectionMode\":\"$videoSectionMode\",")
            append("\"audioSectionMode\":\"$audioSectionMode\",")
            append("\"videoCodec\":${q(videoCodec)},")
            append("\"audioCodec\":${q(audioCodec)},")
            append("\"container\":${q(container)},")
            append("\"qualityMode\":\"$qualityMode\",")
            append("\"crf\":${crf ?: "null"},")
        append("\"cq\":${cq ?: "null"},")
            append("\"videoBitrate\":${videoBitrate ?: "null"},")
            append("\"preset\":${q(preset)},")
            append("\"videoFrameRate\":${q(videoFrameRate)},")
            append("\"audioSampleRate\":${audioSampleRate ?: "null"},")
            append("\"audioBitrate\":${audioBitrate ?: "null"}")
            append(",")
            append("\"audioContainer\":${q(audioContainer)}")
            append(",")
            append("\"videoMaxHeight\":${videoMaxHeight ?: "null"}")
            append("}")
        }
    }

    companion object {
        fun fromJson(raw: String?): OutputPreferences {
            if (raw.isNullOrBlank()) return OutputPreferences()
            return try {
                val o = org.json.JSONObject(raw)
                OutputPreferences(
                    videoSectionMode = SectionMode.valueOf(
                        o.optString("videoSectionMode", "AUTO")
                    ),
                    audioSectionMode = SectionMode.valueOf(
                        o.optString("audioSectionMode", "AUTO")
                    ),
                    videoCodec = o.optString("videoCodec")
                        .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) },
                    audioCodec = o.optString("audioCodec")
                        .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) },
                    container = o.optString("container")
                        .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) },
                    qualityMode = QualityMode.valueOf(
                        o.optString("qualityMode", "BITRATE")
                    ),
                    crf = if (o.isNull("crf")) null else o.optInt("crf"),
                    cq = if (o.isNull("cq")) null else o.optInt("cq"),
                    videoBitrate = if (o.isNull("videoBitrate")) null else o.optLong("videoBitrate"),
                    preset = o.optString("preset").ifBlank { null },
                    videoFrameRate = o.optString("videoFrameRate")
                        .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) },
                    audioSampleRate = if (o.isNull("audioSampleRate")) null else o.optInt("audioSampleRate"),
                    audioBitrate = if (o.isNull("audioBitrate")) null else o.optLong("audioBitrate"),
                    audioContainer = o.optString("audioContainer")
                        .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) },
                    videoMaxHeight = if (o.isNull("videoMaxHeight")) null else o.optInt("videoMaxHeight")
                )
            } catch (_: Exception) {
                OutputPreferences()
            }
        }
    }
}

data class ResolvedOutputPreferences(
    val videoEncoder: String?,
    val audioEncoder: String?,
    val container: String,
    val qualityMode: QualityMode,
    val crf: Int?,
    val videoBitrate: Long?,
    val preset: String?,
    val videoFrameRate: String?,
    val audioSampleRate: Int?,
    val rationale: List<String>
)
