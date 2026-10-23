// app/src/main/java/com/sponsorcut/SegmentParser.kt
package com.sponsorcut

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class SegmentWarning(
    val code: String,
    val message: String,
    val lineNumbers: List<Int> = emptyList()
)

data class SegmentParseResult(
    val segments: List<SponsorSegmentInfo>,
    val warnings: List<SegmentWarning>
)

class SegmentParseException(
    val code: String,
    override val message: String,
    val details: List<String> = emptyList()
) : Exception(message)

object SegmentParser {

    private val timeComponentRegex = Regex("""^\d+(?:[.,]\d+)?$""")
    private val segmentLineRegex =
        Regex("""^\s*(\S+)\s*-\s*(\S+)(?:\s+(.*?))?\s*$""")

    fun parseTime(value: String): Double {
        val raw = value.trim()

        if (raw.equals("end", ignoreCase = true)) {
            return -1.0
        }

        val parts = raw.split(":")

        if (parts.size > 3 || parts.any { !timeComponentRegex.matches(it) }) {
            throw IllegalArgumentException("Invalid timestamp: '$raw'")
        }

        val result = when (parts.size) {
            1 -> parseSeconds(parts[0])
            2 -> {
                val minutes = parseInteger(parts[0], raw)
                val seconds = parseSeconds(parts[1])
                if (seconds >= 60.0) {
                    throw IllegalArgumentException("Seconds must be less than 60: '$raw'")
                }
                minutes * 60.0 + seconds
            }
            else -> {
                val hours = parseInteger(parts[0], raw)
                val minutes = parseInteger(parts[1], raw)
                val seconds = parseSeconds(parts[2])
                if (minutes >= 60 || seconds >= 60) {
                    throw IllegalArgumentException(
                        "Minutes and seconds must be less than 60: '$raw'"
                    )
                }
                hours * 3600.0 + minutes * 60.0 + seconds
            }
        }

        if (!result.isFinite()) {
            throw IllegalArgumentException("Invalid timestamp: '$raw'")
        }

        return result
    }

    fun parseText(
        text: String,
        duration: Double,
        source: String = "<text>"
    ): SegmentParseResult {
        val warnings = mutableListOf<SegmentWarning>()
        val segments = mutableListOf<SponsorSegmentInfo>()

        text.lines().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.trim()

            if (line.isEmpty() || line.startsWith("#")) {
                return@forEachIndexed
            }

            val match = segmentLineRegex.matchEntire(line)
                ?: throw SegmentParseException(
                    code = "invalid_segment_line",
                    message = "$source:$lineNumber: cannot parse segment: '$rawLine'"
                )

            val start = try {
                parseTime(match.groupValues[1])
            } catch (e: IllegalArgumentException) {
                throw SegmentParseException(
                    code = "invalid_timestamp",
                    message = "$source:$lineNumber: ${e.message}",
                    details = listOf(e.message ?: "")
                )
            }

            val endValue = try {
                parseTime(match.groupValues[2])
            } catch (e: IllegalArgumentException) {
                throw SegmentParseException(
                    code = "invalid_timestamp",
                    message = "$source:$lineNumber: ${e.message}",
                    details = listOf(e.message ?: "")
                )
            }

            val endIsOpen = endValue < 0.0
            val end = if (endIsOpen) duration else endValue
            val category = match.groupValues[3].trim()

            if (category.isEmpty()) {
                throw SegmentParseException(
                    code = "missing_category",
                    message = "$source:$lineNumber: segment category is required"
                )
            }

            if (start < 0.0) {
                throw SegmentParseException(
                    code = "negative_start",
                    message = "$source:$lineNumber: negative start time"
                )
            }

            if (end < 0.0) {
                throw SegmentParseException(
                    code = "negative_end",
                    message = "$source:$lineNumber: negative end time"
                )
            }

            if (end <= start) {
                throw SegmentParseException(
                    code = "invalid_range",
                    message = "$source:$lineNumber: end must be greater than start"
                )
            }

            segments += SponsorSegmentInfo(
                category = category,
                start = start,
                end = end,
                endIsOpen = endIsOpen
            )
        }

        return SegmentParseResult(
            segments = normalizeSegments(segments, duration, warnings),
            warnings = warnings
        )
    }

    fun parseJson(
        text: String,
        duration: Double,
        reference: String = "<json>"
    ): SegmentParseResult {
        val warnings = mutableListOf<SegmentWarning>()
        val segments = mutableListOf<SponsorSegmentInfo>()

        val root = try {
            JSONArray(text)
        } catch (e: JSONException) {
            throw SegmentParseException(
                code = "invalid_json",
                message = "$reference: invalid JSON: ${e.message}",
                details = listOf(e.message ?: "")
            )
        }

        for (i in 0 until root.length()) {
            val entryIndex = i + 1
            val item = root.optJSONObject(i)
                ?: throw SegmentParseException(
                    code = "invalid_json_entry",
                    message = "$reference: entry $entryIndex must be a JSON object"
                )

            val interval = item.optJSONArray("segment")
            if (interval == null || interval.length() != 2) {
                throw SegmentParseException(
                    code = "invalid_segment_array",
                    message =
                        "$reference: entry $entryIndex must contain a numeric " +
                            "'segment' array with exactly two values"
                )
            }

            val start = interval.optDouble(0, Double.NaN)
            val end = interval.optDouble(1, Double.NaN)

            if (start.isNaN() || end.isNaN()) {
                throw SegmentParseException(
                    code = "invalid_segment_times",
                    message =
                        "$reference: entry $entryIndex must contain numeric " +
                            "segment start and end values"
                )
            }

            val category = item.optString("category", "")
            if (category.isEmpty()) {
                throw SegmentParseException(
                    code = "missing_category",
                    message =
                        "$reference: entry $entryIndex must contain a text category"
                )
            }

            segments += SponsorSegmentInfo(
                category = category,
                start = start,
                end = end
            )
        }

        return SegmentParseResult(
            segments = normalizeSegments(segments, duration, warnings),
            warnings = warnings
        )
    }

    fun parseSegmentFile(
        text: String,
        duration: Double,
        reference: String
    ): SegmentParseResult {
        return try {
            parseJson(text, duration, reference)
        } catch (jsonError: SegmentParseException) {
            try {
                parseText(text, duration, reference)
            } catch (textError: SegmentParseException) {
                throw SegmentParseException(
                    code = "invalid_segment_file",
                    message =
                        "Unable to parse segment file '$reference' as JSON or TXT.",
                    details = listOf(
                        "JSON reason: ${jsonError.message}",
                        "TXT reason: ${textError.message}"
                    )
                )
            }
        }
    }

    fun normalizeSegments(
        input: List<SponsorSegmentInfo>,
        duration: Double,
        warnings: MutableList<SegmentWarning> = mutableListOf()
    ): List<SponsorSegmentInfo> {
        if (!duration.isFinite() || duration < 0.0) {
            throw SegmentParseException(
                code = "invalid_media_duration",
                message = "Invalid media duration: $duration"
            )
        }

        val cleaned = linkedMapOf<Triple<Double, Double, String>, SponsorSegmentInfo>()

        for (segment in input) {
            val start = segment.start
            val end = segment.end
            val category = segment.category.trim()

            if (!start.isFinite() || !end.isFinite()) {
                throw SegmentParseException(
                    code = "non_finite_segment_time",
                    message = "Segment times must be finite numbers."
                )
            }

            if (start < 0.0 || end < 0.0) {
                throw SegmentParseException(
                    code = "negative_segment_time",
                    message = "Segment times cannot be negative."
                )
            }

            if (end <= start) {
                throw SegmentParseException(
                    code = "invalid_segment_range",
                    message = "Segment end must be greater than its start."
                )
            }

            if (category.isEmpty()) {
                throw SegmentParseException(
                    code = "empty_category",
                    message = "Segment category cannot be empty."
                )
            }

            if (start >= duration) {
                throw SegmentParseException(
                    code = "start_after_media_duration",
                    message =
                        "Segment starts at or after the media duration: " +
                            "start=$start, media duration=$duration."
                )
            }

            val clampedEnd = minOf(end, duration)

            if (clampedEnd <= start) {
                throw SegmentParseException(
                    code = "empty_segment_after_clamp",
                    message =
                        "Segment becomes empty after clamping to media duration."
                )
            }

            val key = Triple(
                start,
                clampedEnd,
                category.lowercase()
            )

            cleaned.putIfAbsent(
                key,
                SponsorSegmentInfo(
                    category = category,
                    start = start,
                    end = clampedEnd,
                    endIsOpen = segment.endIsOpen
                )
            )
        }

        val ordered = cleaned.values.sortedWith(
            compareBy({ it.start }, { it.end })
        )

        val merged = mutableListOf<SponsorSegmentInfo>()
        val mergedCategoryKeys = mutableListOf<MutableSet<String>>()

        for (segment in ordered) {
            if (merged.isEmpty()) {
                merged += segment
                mergedCategoryKeys += mutableSetOf(segment.category.lowercase())
                continue
            }

            val previous = merged.last()

            if (segment.start <= previous.end) {
                if (segment.start < previous.end) {
                    warnings += SegmentWarning(
                        code = "overlapping_segments_merged",
                        message =
                            "Merging overlapping segments " +
                                "${previous.start}–${previous.end} " +
                                "(${previous.category}) and " +
                                "${segment.start}–${segment.end} " +
                                "(${segment.category}).",
                        lineNumbers = emptyList()
                    )
                }

                val categoryKeys = mergedCategoryKeys.last()
                var category = previous.category

                if (segment.category.lowercase() !in categoryKeys) {
                    category = "$category+${segment.category}"
                    categoryKeys += segment.category.lowercase()
                }

                merged[merged.lastIndex] = SponsorSegmentInfo(
                    category = category,
                    start = previous.start,
                    end = maxOf(previous.end, segment.end),
                    endIsOpen = previous.endIsOpen || segment.endIsOpen
                )
            } else {
                merged += segment
                mergedCategoryKeys += mutableSetOf(segment.category.lowercase())
            }
        }

        return merged
    }

    private fun parseInteger(value: String, timestamp: String): Int {
        if (!value.all { it.isDigit() }) {
            throw IllegalArgumentException("Invalid timestamp: '$timestamp'")
        }

        return value.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid timestamp: '$timestamp'")
    }

    private fun parseSeconds(value: String): Double {
        val seconds = value.replace(',', '.').toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid timestamp component: '$value'")

        if (!seconds.isFinite()) {
            throw IllegalArgumentException("Seconds must be finite.")
        }

        return seconds
    }
}