package com.sponsorcut.core.model

enum class StreamSelection {
    BOTH,
    AUDIO_ONLY,
    VIDEO_ONLY
}

enum class JobKind {
    REMOVE_SEGMENTS,
    EXTRACT_AUDIO,
    EXTRACT_VIDEO,
    CONCATENATE
}

enum class ExecutionMode {
    FAST,
    SW_ACCURATE,
    HW_ACCURATE
}

enum class JobStatus {
    PENDING,
    VALIDATING,
    PLANNED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PARTIAL
}

data class TimeRange(
    val startSeconds: Double,
    val endSeconds: Double
)

data class JobInput(
    val uri: String,
    val displayName: String?,
    val durationSeconds: Double?,
    val sizeBytes: Long?
)

data class JobOutput(
    val folderUri: String?,
    val fileName: String?,
    val mimeType: String?
)

data class MediaJob(
    val id: String,
    val kind: JobKind,
    val executionMode: ExecutionMode,
    val inputs: List<JobInput>,
    val output: JobOutput,
    val segmentSource: String? = null,
    val normalizationPolicyJson: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long
)
