package com.sponsorcut.core.model

enum class StreamSelection {
    BOTH,
    AUDIO_ONLY,
    VIDEO_ONLY
}

enum class JobKind {
    REMOVE_SEGMENTS,
    EXTRACT_AUDIO,
    EXTRACT_VIDEO
}

enum class ExecutionMode {
    FAST,
    SW_ACCURATE,
    HW_ACCURATE
}
