# SponsorCut Architecture

Last reviewed: 2026-10-23

SponsorCut is an Android application that removes selected sponsor-like segments from local video files using FFmpegKit. It supports SponsorBlock metadata, locally imported TXT/JSON segment files, and planned manual segmentation. It can remove segments, extract audio or video, and preserve or transcode streams according to a policy-driven plan.

This document describes the current implementation, including intentional design decisions, known limitations, and hard-won compatibility rules.

## 1. High-Level Flow

The normal segment-removal flow is:

1. User selects a local video file.
2. User selects segment categories and, optionally, individual segments.
3. User selects an execution mode:
  - Fast
  - SW Accurate
  - HW Accurate
4. User optionally configures output preferences.
5. MainActivity starts ProcessingService with an ACTION_PROCESS intent.
6. ProcessingService copies the source URI into app cache.
7. FFProbeInspector inspects the cached file.
8. TranscodePolicy builds a ProcessingPlan from probe data, user preferences, encoder availability, and execution mode.
9. FileResolver creates an output target.
10. FfmpegEngine executes the plan.
11. ProcessingService writes the temporary output to the selected destination.
12. ProcessingService reports success, failure, or cancellation and cleans up temporary artifacts.

Conceptually:

    User Input
        |
        v
    MainActivity
        |
        +--> ProcessingService
                  |
                  +--> FileResolver
                  |       |
                  |       +--> cached input file
                  |
                  +--> FFProbeInspector
                  |       |
                  |       +--> VideoInfo
                  |
                  +--> TranscodePolicy
                  |       |
                  |       +--> ProcessingPlan
                  |
                  +--> FfmpegEngine
                  |       |
                  |       +--> FFmpegKit
                  |
                  +--> FileResolver
                          |
                          +--> final output target

## 2. User Input Layer

### 2.1 Job Sources

The app accepts a local media URI selected through the Android file picker or SAF-compatible picker.

The user may choose one of three segment sources:

1. SponsorBlock
2. Local TXT segment file
3. Local JSON segment file

Manual segmentation files are planned as a future source. They will follow the same local-segment ingestion path as TXT and JSON files.

The important architectural point is that local TXT/JSON ingestion completely bypasses SponsorBlock. No network request is made when a local segment file is supplied. The local file becomes the authoritative segment source for that job.

    Local TXT/JSON file
        |
        v
    ProcessingService
        |
        +--> parse file
        |
        +--> category filter
        |
        +--> individual segment selection filter
        |
        +--> sorted removal ranges
        |
        v
    FfmpegEngine

By contrast:

    SponsorBlock
        |
        v
    SponsorBlockClient.fetchRich()
        |
        v
    ProcessingService
        |
        +--> category filter, performed by SponsorBlockClient
        |
        +--> individual segment selection filter
        |
        +--> sorted removal ranges
        |
        v
    FfmpegEngine

### 2.2 Segment Categories

The service receives categories as a string array. If none are supplied, it defaults to:

    ["sponsor"]

For local files, categories are applied after parsing:

    parsed.filter { it.category in categories }

For SponsorBlock, categories are passed to SponsorBlockClient.fetchRich().

After category filtering, the user may further restrict processing to individually selected segments using selectedSegmentIndexes.

The index filter operates on the category-filtered list, not on the raw file contents.

    Raw local segments
        |
        v
    Category filter
        |
        v
    Individual index filter
        |
        v
    selectedSegments

If selectedSegmentIndexes is null, all category-matching segments are used.

If the resulting selectedSegments list is empty, the job terminates successfully with:

    No individual segments were selected for removal. Nothing to do.

If local category filtering produces no matches, the job terminates successfully with:

    No selected categories match the loaded local segments. Nothing to do.

If SponsorBlock returns no segments, the job terminates successfully with:

    SponsorBlock had no segments for ID: <videoId>. Nothing to remove.

### 2.3 Selected Segment Indexes

The service receives:

    EXTRA_SELECTED_SEGMENT_INDEXES

as an IntArray and converts it to a Set<Int>.

It then applies:

    segments.filterIndexed { index, _ ->
        index in selectedSegmentIndexes
    }

This means the indexes refer to positions in the category-filtered segment list.

For local files, that list is the parsed and category-filtered list.

For SponsorBlock, that list is the fetched and category-filtered list.

The selected segments are then sorted by start time:

    val sortedSegs = selectedSegments.sortedBy { it.start }

Sorting is mandatory because:

- Local files may contain out-of-order entries.
- SponsorBlock results are not assumed to be ordered.
- FfmpegEngine derives keep ranges by walking removal ranges in ascending start order.
- Overlapping or unordered removal ranges would otherwise produce incorrect keep ranges.

### 2.4 Known Display Bug

The completion summary currently says:

    Removed ${segments.size} segment(s)

but it should say:

    Removed ${selectedSegments.size} segment(s)

The actual processing correctly uses selectedSegments through sortedSegs. Only the user-facing summary can overstate the removed count when the user deselects individual segments.

## 3. Local Segment File Formats

### 3.1 JSON Format

The JSON format is an array of SponsorBlock-like objects.

Required fields used by SponsorCut:

- category: string
- start: number, seconds
- end: number, seconds

Example:

    [
      {
        "category": "sponsor",
        "actionType": "skip",
        "segment": [0, 18.596],
        "UUID": "13c33103b6944575d1f3705c831d85dd482784dc4200e1d0ebf630c64a747d1f7",
        "videoDuration": 6930.8853,
        "locked": 0,
        "votes": 0,
        "description": ""
      },
      {
        "category": "intro",
        "actionType": "skip",
        "segment": [2293.668, 2307.218],
        "UUID": "1ea0272e05e03e2154cd7ad681c856e5700ce3dbde3e3e85b33bd45406b0bd9f7",
        "videoDuration": 6930.8853,
        "locked": 0,
        "votes": 0,
        "description": ""
      }
    ]

The parser currently reads:

    obj.getString("category")
    obj.getDouble("start")
    obj.getDouble("end")

It does not currently use:

- actionType
- segment
- UUID
- videoDuration
- locked
- votes
- description

Those fields are tolerated because JSON parsing ignores unused object members.

The canonical SponsorBlock-style segment field is:

    "segment": [start, end]

SponsorCut's current local JSON ingestion expects flattened top-level start and end fields:

    "start": 0.0,
    "end": 18.596

A SponsorBlock JSON export that only contains the nested segment array but lacks top-level start and end fields will not parse correctly under the current implementation.

This is an important compatibility distinction:

    SponsorBlock-style JSON:
        "segment": [0, 18.596]

    SponsorCut local JSON currently requires:
        "start": 0,
        "end": 18.596

Future work should normalize both forms during ingestion.

### 3.2 TXT Format

The TXT format is line-based:

    <start timestamp> - <end timestamp> <Category>

Example:

    00:00:00,000 - 00:01:04,240 Sponsor
    00:11:04,980 - 00:14:32,720 Sponsor
    00:22:07,960 - 00:22:29,360 Intermission
    02:00:37,760 - end Sponsor

The category is the trailing text after the second timestamp.

The token:

    end

is duration-dependent. It means "until the end of the video."

When the source video duration is known, end is resolved to that duration.

When the duration is unknown, an end token cannot be safely resolved and the affected line should be rejected or handled according to the ingestion policy.

### 3.3 TXT Timestamp Rules

TXT ingestion is intentionally forgiving.

Accepted separators:

    -
    –

Whitespace around separators is accepted.

Accepted millisecond separators:

    ,
    .

For example, all of these are accepted:

    00:01:04,240
    00:01:04.240

Accepted whitespace:

- spaces
- tabs
- repeated whitespace

Accepted timestamp shapes include:

    HH:MM:SS,mmm
    MM:SS,mmm
    SS,mmm
    HH:MM:SS.mmm
    MM:SS.mmm
    SS.mmm

Missing hours are acceptable.

Missing minutes are acceptable.

Examples:

    01:04,240
    04,240
    00:01:04.240
    1:04.240

The parser should normalize these into floating-point seconds.

### 3.4 TXT Validation and Rejection

A TXT line is rejected when it cannot produce a valid removal range.

Typical rejection cases:

1. Malformed timestamp.
2. Negative timestamp.
3. Start timestamp after the known video duration.
4. End timestamp after the known video duration, unless the token is end.
5. Non-monotonic timestamp pair, where end is not greater than start.
6. A duration-dependent end token resolved to a value before its matching start.

The non-monotonic case can occur when a shorter video is paired with a segment file containing:

    02:00:37,760 - end Sponsor

If the video is, for example, only 90 seconds long, then the parsed start is already beyond the video duration. Depending on implementation, end may resolve to the video duration and become less than start. That produces an invalid range:

    start > end

Such a range must not be passed to FfmpegEngine.

The ingestion mechanism should reject the invalid line, log the reason, and continue with valid lines where possible.

The forgiving philosophy is:

- Accept flexible timestamp formatting.
- Normalize commas and periods.
- Tolerate variable whitespace.
- Accept abbreviated timestamps.
- Reject only ranges that cannot be meaningfully processed.
- Preserve diagnostic information for rejected lines.

### 3.5 Segment Ordering and Overlap

After parsing and validation, local segments are:

1. Category-filtered.
2. Individually selected, if applicable.
3. Sorted by start time.
4. Converted to removal ranges.
5. Passed to FfmpegEngine.

FfmpegEngine further converts removal ranges into keep ranges.

Overlapping removal ranges are coalesced implicitly by the keep-range derivation loop:

    var lastEnd = 0.0
    for ((start, end) in sorted) {
        if (start > lastEnd + 0.01) keepRanges += lastEnd to start
        if (end > lastEnd) lastEnd = end
    }
    keepRanges += lastEnd to Double.MAX_VALUE

This means:

- A later segment beginning before the previous segment ends does not create a new keep range.
- The effective removed region extends to the maximum end seen so far.
- A 0.01-second tolerance prevents meaningless micro-gaps from creating tiny keep ranges.

## 4. ProcessingService

ProcessingService is a foreground Android service.

### 4.1 Intent Contract

Process action:

    ACTION_PROCESS = "com.sponsorcut.PROCESS"

Cancel action:

    ACTION_CANCEL = "com.sponsorcut.CANCEL"

Important extras:

    EXTRA_URI
    EXTRA_VIDEO_ID
    EXTRA_ID_SOURCE
    EXTRA_OUTPUT_FOLDER_URI
    EXTRA_FRAME_ACCURATE
    EXTRA_CATEGORIES
    EXTRA_HW_ACCURATE
    EXTRA_REMOVE_SEGMENTS
    EXTRA_LOCAL_SEGMENTS_JSON
    EXTRA_SELECTED_SEGMENT_INDEXES
    EXTRA_STREAM_SELECTION
    EXTRA_OUTPUT_PREFERENCES_JSON

Defaults:

    categories = ["sponsor"]
    streamSelection = BOTH
    removeSegments = true
    frameAccurate = false
    hwAccurate = false

### 4.2 Lifecycle

On ACTION_PROCESS, the service:

1. Sets isProcessingActive = true.
2. Clears cancelRequested.
3. Starts elapsed-time updates.
4. Removes stale cache artifacts.
5. Acquires a partial wake lock for up to two hours.
6. Clears the diagnostic log.
7. Records power diagnostics.
8. Copies the source URI to cache.
9. Inspects the cached file.
10. Builds a ProcessingPlan.
11. Creates the output target.
12. Loads or fetches segments.
13. Filters and sorts segments.
14. Calls FfmpegEngine.process().
15. Writes the temporary output to the output target.
16. Re-inspects the output.
17. Builds a completion summary.
18. Broadcasts success.
19. Cleans up.

On ACTION_CANCEL, the service:

1. Sets cancelRequested = true.
2. Calls FFmpegKit.cancel().
3. Returns immediately.

Cancellation is cooperative at the FFmpeg level. FFmpegKit cancels the active session; the processing thread then observes cancellation through FFmpeg return codes or explicit checks.

### 4.3 Temporary Artifact Cleanup

Before each run, the service removes stale cache files whose names begin with:

    input_
    part_
    concat_
    singlepass_
    processed_

This prevents failed or cancelled prior runs from accumulating cache files.

After a run, the service deletes:

- The temporary processed output.
- The cached input file.

On failure or cancellation, it also deletes the output target so no partial output remains visible to the user.

### 4.4 Wake Lock and Power Diagnostics

The service acquires:

    PowerManager.PARTIAL_WAKE_LOCK
    tag: sponsorcut:processing
    timeout: 2 hours

It records:

- Charging state
- Battery temperature
- Power-save mode
- Thermal status

This information is written to DiagLog to help diagnose device-dependent encoding failures, throttling, and long-job interruptions.

### 4.5 Progress and Broadcasts

The service emits package-scoped broadcasts:

    BROADCAST_PROGRESS = "com.sponsorcut.PROGRESS"

Text progress extras:

    EXTRA_PROGRESS_TEXT
    EXTRA_DONE
    EXTRA_ERROR
    EXTRA_CANCELLED

Numeric progress extras:

    EXTRA_PROGRESS_CURRENT
    EXTRA_PROGRESS_TOTAL

Terminal results are persisted to SharedPreferences before broadcast:

    last_completed_status
    last_completed_log

This allows MainActivity to restore the last result even if its receiver was unregistered while the activity was paused.

### 4.6 Notifications

The service uses:

    CHANNEL_ID = "sponsorcut_processing"
    NOTIF_ID = 1
    NOTIF_DONE_ID = 2

NOTIF_ID is the ongoing foreground/progress notification.

NOTIF_DONE_ID is the separate completion notification.

The progress notification can show:

- Text progress
- Part current/total
- Determinate progress bar
- Elapsed time

The completion notification reports success, failure, or cancellation.

## 5. FileResolver

FileResolver is the storage boundary.

### 5.1 Input Caching

    uriToFile(context, uri)

Copies the selected source into cache using:

    input_<timestamp>.<original extension>

The original extension is preserved because downstream policy and engine behavior may depend on it.

The display name is obtained through:

    OpenableColumns.DISPLAY_NAME

If unavailable, it falls back to:

    uri.lastPathSegment ?: "video.mp4"

### 5.2 Output Targets

OutputTarget is a sealed class:

    OutputTarget.FileTarget
    OutputTarget.UriTarget

FileTarget represents a direct java.io.File destination.

UriTarget represents a content URI destination, typically MediaStore or SAF.

### 5.3 SAF Tree Output

    createOutputTargetInTree()

Uses DocumentFile to:

1. Open the selected tree URI.
2. Delete an existing file with the same name.
3. Create the output file.
4. Return an OutputTarget.UriTarget.

### 5.4 Same-Folder Output

    createOutputTarget()

Attempts to resolve a writable real filesystem path near the source.

If successful, it writes beside the source using a FileTarget.

If not, it falls back to MediaStore or legacy public storage.

### 5.5 MediaStore Fallback

On Android 10 and newer:

- Video goes to Movies/SponsorCut.
- Audio goes to Music/SponsorCut.

On older Android versions:

- Video goes to public Movies/SponsorCut.
- Audio goes to public Music/SponsorCut.

### 5.6 Output Writing and Deletion

    writeToOutputTarget()

Copies the temporary FFmpeg output into the final destination.

    deleteOutputTarget()

Deletes a FileTarget directly or deletes a UriTarget through ContentResolver.

This is used on failure and cancellation to avoid leaving partial output files.

### 5.7 Diagnostic Log Export

    saveTextToOutputFolder()

Writes a plain-text diagnostic log into a user-selected SAF tree.

It deletes an existing file of the same name before creating the new one.

## 6. FFProbeInspector

FFProbeInspector is the single source of truth for media characteristics.

No other component is permitted to call ffprobe directly.

### 6.1 Inspection Strategy

FFProbeInspector uses two paths:

1. Structured FFprobeKit API:

   FFprobeKit.getMediaInformation()

2. Raw JSON ffprobe execution:

   ffprobe -v quiet -print_format json -show_streams -show_format ...

It merges both results.

The JSON path is especially important because it provides:

- Container format
- TS detection
- Attached-picture rejection
- More reliable bitrate fields in some cases

### 6.2 VideoInfo

The central probe result is:

    VideoInfo

Fields include:

    videoCodec
    width
    height
    fps
    pixFmt
    videoBitrate
    videoIndex
    audioCodec
    sampleRate
    audioBitrate
    audioChannels
    audioIndex
    durationSec
    containerBitrate
    containerFormat
    isTsEncapsulated

Derived fields:

    pixels = width * height

    summaryLine = human-readable media summary

### 6.3 Attached Picture Handling

The structured API can report embedded album art as a video stream.

The JSON path explicitly skips streams where:

    disposition.attached_pic == 1

The merge logic also rejects image-like codecs:

    mjpeg
    png
    bmp
    gif
    webp
    tiff

This prevents cover art from being mistaken for the primary video stream.

### 6.4 Bitrate Preference

During merge:

- JSON video bitrate is preferred over API video bitrate.
- JSON audio bitrate is preferred over API audio bitrate.
- Other fields use a first-non-null merge strategy.

### 6.5 TS Detection

TS detection is critical.

The authoritative signal is:

    containerFormat.contains("mpegts")

If true:

    isTsEncapsulated = true

There is also a legacy H.264 Annex B hint:

    stream.is_avc == "false"

This is not a general TS indicator. It is H.264-specific and must not be treated as a generic MPEG-TS or HEVC signal.

Future work should add explicit HEVC Annex B detection if needed.

### 6.6 Failure Behavior

If both inspection paths fail, FFProbeInspector returns a minimal stub:

    videoCodec = "unknown"
    audioCodec = "unknown"

This generally causes TranscodePolicy to fail with a clear policy error rather than silently producing a bad transcode.

## 7. EncoderCatalog

EncoderCatalog discovers which FFmpeg encoders are available on the installed FFmpegKit build.

### 7.1 Discovery

It runs:

    ffmpeg -hide_banner -encoders

It searches the output for each relevant encoder name using word-boundary regex matching.

The relevant encoder list is:

Video:

    libx264
    libx265
    libvpx-vp9
    libsvtav1
    libaom-av1
    mpeg4
    h264_mediacodec
    hevc_mediacodec

Audio:

    aac
    opus
    libmp3lame
    vorbis
    flac

### 7.2 Codec Family Mapping

Input codecs are mapped to families:

    h264, avc          -> H.264
    hevc, h265         -> H.265/HEVC
    vp9                -> VP9
    av1                -> AV1
    mpeg4              -> MPEG-4 Part 2
    aac                -> AAC
    opus               -> Opus
    mp3                -> MP3
    vorbis             -> Vorbis
    flac               -> FLAC

### 7.3 UI Filtering

    videoOptions()

Orders families as:

    H.264
    H.265/HEVC
    VP9
    AV1
    MPEG-4 Part 2

Then filters by mode:

    FAST         -> software encoders only
    SW_ACCURATE  -> software encoders only
    HW_ACCURATE  -> hardware encoders only

Important correction: the current UI filtering excludes hardware encoders from Fast and SW Accurate lists, and excludes software encoders from HW Accurate lists. This is correct for mode-specific selection.

However, the policy fallback hierarchy includes VP9 and AV1 before MPEG-4 Part 2. On the tested device, VP9 and AV1 encoders were not detected, so the fallback visibly proceeded to MPEG-4 Part 2. On devices where libvpx-vp9 or libsvtav1/libaom-av1 are available, those encoders participate in the fallback path before MPEG-4 Part 2.

## 8. TranscodePolicy

TranscodePolicy converts probe data, user preferences, execution mode, and encoder availability into a ProcessingPlan.

It is the decision layer.

FfmpegEngine is the execution layer.

FfmpegEngine must not make codec, container, quality, or fallback policy decisions.

### 8.1 ProcessingPlan

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
        val useSlowSeek: Boolean = false,
        val singlePassFilter: Boolean = false,
        val dropVideo: Boolean = false,
        val dropAudio: Boolean = false,
        val jobKind: JobKind = JobKind.REMOVE_SEGMENTS,
        val removeSegments: Boolean = true,
        val streamSelection: StreamSelection = StreamSelection.BOTH
    )

### 8.2 Execution Modes

    FAST
    SW_ACCURATE
    HW_ACCURATE

Mode selection:

    hwAccurate  -> HW_ACCURATE
    frameAccurate -> SW_ACCURATE
    otherwise   -> FAST

### 8.3 Core Copy Rules

    canCopyVideo = mode == FAST && resolved video encoder == null
    canCopyAudio = mode == FAST && resolved audio encoder == null

Therefore:

- Fast mode may stream-copy.
- SW Accurate always re-encodes.
- HW Accurate always re-encodes.

### 8.4 Single-Pass Rule

For BOTH-stream segmented jobs:

    singlePassFilter =
        removeSegments &&
        mode != FAST &&
        !canCopyVideo

Since Accurate modes always re-encode video, this effectively means:

    singlePassFilter = removeSegments && mode is Accurate

For extraction jobs:

    Audio-only:
        singlePassFilter = removeSegments && !canCopyAudio

    Video-only:
        singlePassFilter = removeSegments && !canCopyVideo

### 8.5 Slow Seek Rule

    useSlowSeek = info.isTsEncapsulated

This is intentionally conservative.

MPEG-TS inputs require slow seek. See the MPEG-TS chapter.

### 8.6 Complexity Classification

    FAST -> LOW

Otherwise:

    >= 1920x1080 and fps >= 50 -> HIGH
    >= 1920x1080               -> MEDIUM
    otherwise                  -> LOW

## 9. Full Transcode Policy Matrix

### 9.1 BOTH Streams, Segment Removal

| Mode | Video action | Audio action | Container | Execution path |
|---|---|---|---|---|
| Fast, video copy, audio copy | copy | copy | Auto or custom | Fast part extraction, concat |
| Fast, video copy, audio transcode | copy | transcode | Auto or custom | Fast part extraction, concat |
| Fast, video transcode, audio copy | transcode | copy | Auto or custom | Fast part extraction, concat |
| Fast, video transcode, audio transcode | transcode | transcode | Auto or custom | Fast part extraction, concat |
| SW Accurate | transcode | transcode or copy per policy | Auto or custom | Single-pass filter_complex |
| HW Accurate | transcode | transcode or copy per policy | Auto or custom | Single-pass filter_complex |

In practice, Fast resolves both encoders to null unless custom preferences force a codec. Therefore the normal Fast path is:

    video copy + audio copy

But the policy supports mixed copy/transcode cases.

### 9.2 BOTH Streams, No Segment Removal

When removeSegments = false:

| Mode | Video action | Audio action | Path |
|---|---|---|---|
| Fast | copy if policy allows | copy if policy allows | processBothNoSegments() |
| SW Accurate | transcode | transcode | processBothNoSegments() |
| HW Accurate | transcode | transcode | processBothNoSegments() |

No trim, concat, or filter_complex is used.

### 9.3 Audio-Only Extraction

| Mode | Audio action | Container | Path |
|---|---|---|---|
| Fast, audio copy | copy | Derived from input audio codec or custom container | runExtraction() |
| Fast, audio transcode | transcode | Derived from selected encoder or custom container | runExtraction() |
| SW Accurate | transcode | Derived from selected encoder or custom container | runExtraction(), optionally segmented |
| HW Accurate | transcode | Derived from selected encoder or custom container | runExtraction(), optionally segmented |

Audio-only extraction always drops video:

    -vn

If segmented:

    singlePassFilter = removeSegments && !canCopyAudio

### 9.4 Video-Only Extraction

| Mode | Video action | Container | Path |
|---|---|---|---|
| Fast, video copy | copy | Derived from input video codec or custom container | runExtraction() |
| Fast, video transcode | transcode | Derived from selected encoder or custom container | runExtraction() |
| SW Accurate | transcode | Derived from selected encoder or custom container | runExtraction(), optionally segmented |
| HW Accurate | transcode | Derived from selected encoder or custom container | runExtraction(), optionally segmented |

Video-only extraction always drops audio:

    -an

If segmented:

    singlePassFilter = removeSegments && !canCopyVideo

### 9.5 Video Encoder Resolution

Fast:

    Always null.
    Rationale: Fast stream copy.

Custom video section:

    If videoCodec == null:
        Auto behavior.
    Otherwise:
        Encoder must exist.
        Encoder must be available.
        SW Accurate cannot select hardware encoder.
        HW Accurate cannot select software encoder.

SW Accurate Auto:

    1. Input-family software encoder.
    2. H.264 fallback: libx264.
    3. VP9 fallback: libvpx-vp9.
    4. AV1 fallback: libsvtav1.
    5. MPEG-4 Part 2 fallback: mpeg4.

HW Accurate Auto:

    H.264:
        1. h264_mediacodec
        2. SW fallback for same family, if available

    H.265/HEVC:
        1. hevc_mediacodec
        2. SW fallback for same family, if available

    VP9:
        No HW Auto mapping.
        Error: choose SW or custom HW encoder.

    AV1:
        No HW Auto mapping.
        Error: choose SW or custom HW encoder.

    MPEG-4 Part 2:
        No HW Auto mapping.
        Error: choose SW or custom HW encoder.

### 9.6 Audio Encoder Resolution

Fast:

    Always null.
    Rationale: Fast audio stream copy.

Custom audio section:

    Requested encoder must exist and be available.

Audio Auto:

    1. Match input family:
        AAC    -> aac
        Opus   -> libopus
        MP3    -> libmp3lame
        Vorbis -> libvorbis
        FLAC   -> flac

    2. If unavailable, choose default audio for selected video family:
        H.264             -> aac
        H.265/HEVC        -> aac
        VP9               -> opus
        AV1               -> aac
        MPEG-4 Part 2     -> aac

    3. If that fallback is unavailable:
        Error.

### 9.7 Container Resolution

Custom container:

    If video section is CUSTOM and container is set:
        Use requested container.
        Validate against video family.
        Validate against audio family.

Auto container:

    1. Determine allowed containers for resolved video family.
    2. Determine input container formats.
    3. If input is MPEG-TS and TS is allowed for the selected video family:
        Use ts.
    4. Otherwise, if the input container is allowed for the selected video family:
        Preserve it.
    5. Otherwise:
        VP9 -> webm
        Everything else -> mp4
    6. Validate final container against video and audio families.

Container compatibility:

    H.264:
        mp4, mkv, mov, ts, m2ts, avi

    H.265/HEVC:
        mp4, mkv, mov, ts, m2ts

    VP9:
        webm, mkv

    AV1:
        mp4, mkv, webm

    MPEG-4 Part 2:
        mp4, mkv, avi

    AAC:
        mp4, mkv, mov, m4a, aac, ts, m2ts

    Opus:
        webm, mkv, opus, oga, ogg

    MP3:
        mp3, mkv, avi

    Vorbis:
        webm, mkv, ogg, oga

    FLAC:
        mkv, flac

### 9.8 Quality Resolution

Fast:

    QualityMode.BITRATE
    bitrate = null

SW Accurate:

    libx264 and libx265 support CRF.
    Other software encoders use BITRATE.

HW Accurate:

    MediaCodec encoders may use CQ in policy.
    In practice, CQ is not operational on the current FFmpegKit build.
    See CQ section.

CRF:

    Only for libx264 and libx265.
    Default CRF = 23.
    Valid range = 0..51.

CQ:

    Only for h264_mediacodec and hevc_mediacodec.
    Uses:
        -bitrate_mode cq
        -global_quality <cq>
        -g <gop>

    Currently not operational.

BITRATE:

    Priority:
        1. User-requested video bitrate.
        2. Input stream video bitrate, if plausible.
        3. Container bitrate minus audio bitrate, if plausible.
        4. Resolution-based fallback.

    Plausible video bitrate threshold:
        64,000 bps

    Resolution fallback:
        >= 1920x1080 -> 1500k
        >= 1280x720  -> 1000k
        >= 854x480   -> 600k
        otherwise    -> 400k

Audio bitrate:

    Priority:
        1. User-requested audio bitrate.
        2. Source audio bitrate, if plausible.
        3. 128k fallback.

    Plausible audio bitrate threshold:
        32,000 bps

### 9.9 Frame Rate and Scaling

Frame rate:

    Requested frame rate is applied only when it differs from input fps by more than 0.01.

    This avoids normalizing VFR or fractional-rate MPEG-TS input unnecessarily.

Downscaling:

    Allowed.

    Uses:
        -vf scale=-2:<maxHeight>

    Only when input height exceeds requested maxHeight.

Upscaling:

    Never performed.

### 9.10 Fallback Encoder Args

For HW Accurate:

    If quality bitrate exists:
        -c:v <encoder>
        -b:v <bitrate>k
        -pix_fmt yuv420p

    If no bitrate:
        Defaults to 4000k.

For non-HW Accurate:

    Current implementation returns:
        -c:v libx264
        -crf 23

Known limitation:

    This fallback assumes libx264 is available.
    If libx264 is unavailable, the fallback may itself fail.

Audio fallback:

    -c:a aac
    -b:a 128k

## 10. FfmpegEngine

FfmpegEngine is the execution layer.

It receives a ProcessingPlan and performs FFmpeg operations.

It never calls ffprobe directly.

### 10.1 process() Dispatch

    fun process(...)

Dispatch order:

    1. If plan.removeSegments == false:
        processStreamSelection()
        return

    2. If segments.isEmpty():
        return

    3. Sort removal ranges.

    4. Derive keep ranges.

    5. If plan.singlePassFilter:
        processSinglePass()
        return

    6. Otherwise:
        Fast part-based extraction and concat.

### 10.2 Keep Range Derivation

Input:

    sorted removal ranges

Algorithm:

    lastEnd = 0

    For each removal range:
        If start > lastEnd + 0.01:
            Add keep range lastEnd..start
        If end > lastEnd:
            lastEnd = end

    Add final keep range:
        lastEnd..Double.MAX_VALUE

The final range means "from last removed end to EOF."

Zero-length ranges are skipped.

### 10.3 Fast Part-Based Path

For each keep range:

1. Create:

   part_<index>_<timestamp>.<extension>

2. Compute duration:
  - Finite range: end - start.
  - EOF range: fileDuration - start, if known.
  - Unknown: null.

3. Show progress:
  - Part index/total.
  - Start/end timestamps.
  - Elapsed time.
  - Estimated time remaining.

4. Run executeSegment().

5. On failure:
  - Retry with slow seek, if not copy-only.
  - Retry with conservative fallback profile, if fallback differs from primary.
  - HW Accurate does not silently fall back to SW.

6. Add successful non-empty part to parts list.

7. Update regression data.

If only one part is produced:

    Copy it directly to output.

If multiple parts are produced:

1. Write concat list:

   file '<absolute path>'

2. Run:

   ffmpeg -y -f concat -safe 0 -i <concat list> -c copy [-f <muxer>] <concat output>

3. Copy concat output to final output.
4. Delete concat artifacts.

### 10.4 Fast Progress and Regression

The Fast path records:

    duration
    processing time

It computes a per-segment linear regression:

    processing_time_s = a * duration_time_s + b

Where:

    a = per-second processing slope
    b = fixed per-segment overhead

This regression powers ETA estimation and the completion-screen rate equation.

With one data point:

    a = processing_time / duration
    b = 0

With two or more points:

    Standard least-squares regression is used.

### 10.5 executeSegment()

executeSegment() handles one kept range.

It supports:

- Fast seek
- Slow seek
- Stream copy
- Video transcode
- Audio transcode
- Audio-only
- Video-only
- Hardware decoder args
- Fallback video args
- Fallback audio args
- Explicit output muxer
- Negative timestamp correction

Fast seek:

    -ss <start>
    [-t <duration>]
    [-i input]

Slow seek:

    For non-copy:
        decoder args
        -i input
        -ss <start>
        [-t <duration>]
        -map 0

    For copy:
        -ss <start>
        [-t <duration>]
        decoder args
        -i input
        -map 0

Copy:

    -c copy
    or separate:
    -c:v copy
    -c:a copy

    -avoid_negative_ts make_zero

Transcode:

    -c:v <encoder args or copy>
    -c:a <encoder args or copy>

    -avoid_negative_ts make_zero

### 10.6 Single-Pass Path

processSinglePass() processes all keep ranges in one FFmpeg invocation.

It is used for Accurate segmented jobs.

Benefits:

- Encoder initialized once.
- No concat step.
- No per-part encoder startup overhead.
- Better suited to MediaCodec.

Cost:

- One large filter_complex graph.
- Less granular progress.
- A failure affects the entire job.

### 10.7 Single-Pass Input Strategy

For each keep range, the engine adds one input:

    -i input

Fast path:

    -ss <start>
    -i input

TS slow-seek path:

    -i input

No pre-seek.

### 10.8 Single-Pass Filter Graph

For each branch:

Fast finite range:

    Video:
        [i:v]trim=start=0:end=<duration>,setpts=PTS-STARTPTS[vi]

    Audio:
        [i:a]atrim=start=0:end=<duration>,asetpts=PTS-STARTPTS[ai]

Fast EOF range:

    Video:
        [i:v]setpts=PTS-STARTPTS[vi]

    Audio:
        [i:a]asetpts=PTS-STARTPTS[ai]

TS slow-seek finite range:

    Video:
        [i:v]trim=start=<start>:end=<end>,setpts=PTS-STARTPTS[vi]

    Audio:
        [i:a]atrim=start=<start>:end=<end>,asetpts=PTS-STARTPTS[ai]

TS slow-seek EOF range:

    Video:
        [i:v]trim=start=<start>,setpts=PTS-STARTPTS[vi]

    Audio:
        [i:a]atrim=start=<start>,asetpts=PTS-STARTPTS[ai]

Concat:

    [v0][a0][v1][a1]...
    concat=n=<branch count>:v=<video flag>:a=<audio flag>
    [outv][outa]

Mapping:

    -map [outv]
    -map [outa]

### 10.9 Single-Pass Graph Validation

validateSinglePassGraph() enforces structural correctness.

It checks:

1. Concat branch count:

   concat=n=<expected>

2. TS slow-seek video:
  - Every branch must have trim.
  - No bare setpts branch is allowed.

3. TS slow-seek audio:
  - Every branch must have atrim.
  - No bare asetpts branch is allowed.

4. Fast video:
  - Every branch must have either trim or setpts.

5. Fast audio:
  - Every branch must have either atrim or asetpts.

This validation exists because an incorrect graph can silently consume the entire source or duplicate branches during concat.

### 10.10 Single-Pass Fallback

The code contains a fallback retry path:

    hasFallbackProfile = false

Because it is hard-coded false, the single-pass fallback retry is currently dead code.

The Fast path does use fallback profiles.

### 10.11 Segmentless Processing

When removeSegments = false:

    BOTH:
        processBothNoSegments()

    AUDIO_ONLY:
        runExtraction()

    VIDEO_ONLY:
        runExtraction()

processBothNoSegments():

    -y
    -i input
    [-c:v copy | video encoder args]
    [-c:a copy | audio encoder args]
    [-f muxer]
    output

runExtraction():

    Audio-only:
        -y
        -i input
        -vn
        [-c:a copy | audio encoder args]
        [-f muxer]
        output

    Video-only:
        -y
        -i input
        -an
        [-c:v copy | video encoder args]
        [-f muxer]
        output

## 11. CQ and Hardware Quality Control

### 11.1 Current Status

CQ is not operational.

Attempting CQ produced an FFmpeg error.

The policy and argument-generation code exist, but the feature is not exposed as a working user-facing path.

It will be retried after an FFmpegKitNext upgrade.

### 11.2 Intended CQ Behavior

For:

    h264_mediacodec
    hevc_mediacodec

The intended arguments are:

    -c:v <encoder>
    -bitrate_mode cq
    -global_quality <cq>
    -g <gop>

The GOP size is computed as:

    requested fps rounded to integer, minimum 30

If no requested fps:

    GOP = 120

The GOP guard exists because MediaCodec derives i-frame interval from GOP size and FPS. If the rounded interval becomes zero, encoder configuration can fail.

### 11.3 CRF Availability

CRF is not available on MediaCodec H.264 or HEVC encoders.

CRF is available for:

    libx264
    libx265

The policy intentionally restricts CRF to those encoders.

### 11.4 HEVC CQ Attempt

An attempt was made to implement CQ for HEVC MediaCodec.

FFmpeg rejected it.

Therefore:

- H.264 MediaCodec CQ: code exists, not operational.
- HEVC MediaCodec CQ: code exists, not operational.
- CRF on MediaCodec: unsupported.
- Current reliable Accurate quality control: bitrate-based.

This is a known FFmpegKit limitation, not a SponsorCut policy-design failure.

## 12. MPEG-TS: The Hard Case

MPEG-TS was the most difficult input class to make reliably transcodable.

It required a dedicated slow-seek strategy.

### 12.1 Why TS Is Different

MPEG-TS commonly uses:

- Annex B start codes.
- Variable or irregular packet timing.
- Independent video and audio presentation timing.
- Demux-level seek behavior that can land video and audio at different points.
- Fractional or unusual frame rates.
- Long files where small timing drift becomes visible.

A naive approach such as:

    ffmpeg -ss <time> -i input.ts ...

can cause video and audio to begin at different demux positions.

That produces:

- Audio/video boundary drift.
- Missing or duplicated beginning frames.
- Incorrect trim boundaries.
- Concatenation artifacts.
- Duration loss.

### 12.2 Failed Approaches

We attempted to make FFmpeg convert TS without informing it of the input nature.

Result:

    Error.

We attempted to inform FFmpeg of the TS nature and convert it.

Result:

    A different error.

The conclusion was that ordinary fast seeking and naive conversion were not reliable enough for TS inputs.

### 12.3 Final TS Strategy

The final strategy is:

    useSlowSeek = true

For Fast part extraction:

    Slow seek avoids pre-seek-related demux divergence.

For Accurate single-pass:

    Do not pre-seek inputs.
    Add one full input per keep range.
    Use absolute timestamps in trim/atrim.

Example:

    -i input.ts
    -i input.ts
    -filter_complex "
      [0:v]trim=start=100.000:end=200.000,setpts=PTS-STARTPTS[v0];
      [0:a]atrim=start=100.000:end=200.000,asetpts=PTS-STARTPTS[a0];
      [1:v]trim=start=300.000:end=400.000,setpts=PTS-STARTPTS[v1];
      [1:a]atrim=start=300.000:end=400.000,asetpts=PTS-STARTPTS[a1];
      [v0][a0][v1][a1]concat=n=2:v=1:a=1[outv][outa]
    "

This guarantees that video and audio are trimmed from the same absolute source timestamps.

### 12.4 Why Absolute Trim Matters

With pre-seeked inputs:

    -ss 100 -i input.ts

FFmpeg may seek video and audio independently.

With absolute trim:

    [0:v]trim=start=100:end=200
    [0:a]atrim=start=100:end=200

Both streams are cut from the same source timeline.

This is the key TS correctness rule.

### 12.5 Graph Validation for TS

Because a bare setpts branch would consume the entire source, the validator rejects:

    [0:v]setpts=PTS-STARTPTS[v0]

in TS slow-seek mode.

Every TS branch must contain:

    trim=
    atrim=

Likewise, bare asetpts branches are rejected.

This validation prevents a subtle class of graph errors that would otherwise produce duplicated or full-length streams.

### 12.6 Frame Rate Preservation

Forcing -r on TS input can normalize VFR or fractional-rate timing.

That has caused MediaCodec video-duration loss on long files.

Therefore:

    Frame rate is forced only when requested differs from source fps by more than 0.01.

### 12.7 TS Outcome

MPEG-TS is now barely but reliably transcodable under the following conditions:

- TS is detected from container format.
- useSlowSeek is enabled.
- Fast part extraction uses slow seek.
- Accurate single-pass uses absolute trim/atrim.
- The filter graph is validated.
- Frame rate is not unnecessarily forced.

This was achieved only after extensive experimentation and multiple failed approaches.

## 13. Output Naming

    outputFileNameFromSource()

Produces:

    <base>_clean_<unix seconds>.<extension>

Example:

    video.mp4
    ->
    video_clean_1760000000.mp4

The extension is normally determined by TranscodePolicy, not blindly inherited from the input.

## 14. Diagnostics

DiagLog is the central diagnostic journal.

It records:

- Service lifecycle.
- Input caching.
- FFProbe results.
- Encoder discovery.
- Policy rationale.
- FFmpeg arguments.
- FFmpeg return codes.
- FFmpeg logs.
- Failed-session output.
- Regression data.
- Power and thermal state.
- Cleanup actions.
- User-facing errors.

Diagnostic logs can be exported to a user-selected SAF folder.

## 15. Known Limitations

1. CQ is not operational on the current FFmpegKit build.
2. CRF is unavailable for MediaCodec H.264 and HEVC.
3. HEVC MediaCodec CQ was rejected by FFmpeg.
4. Single-pass fallback retry is dead code because hasFallbackProfile is false.
5. Non-HW fallback video args assume libx264 availability.
6. Local JSON ingestion currently expects top-level start and end fields, not only the nested SponsorBlock segment array.
7. The success summary uses segments.size instead of selectedSegments.size.
8. VP9 and AV1 fallbacks may be invisible on devices where those encoders are unavailable.
9. MPEG-TS requires slow seek and cannot be treated like ordinary MP4/MKV input.
10. Manual segmentation is planned but not yet implemented.

## 16. Architectural Invariants

1. FFProbeInspector is the only ffprobe boundary.
2. TranscodePolicy is the only policy decision layer.
3. FfmpegEngine is the only FFmpeg execution layer.
4. ProcessingService orchestrates but does not encode.
5. Local TXT/JSON ingestion bypasses SponsorBlock entirely.
6. Segment indexes apply after category filtering.
7. Removal ranges are always sorted before keep-range derivation.
8. TS inputs always use slow seek.
9. TS single-pass always uses absolute trim/atrim.
10. Accurate modes always re-encode.
11. HW Accurate never silently falls back to software encoding.
12. Partial outputs are deleted on failure or cancellation.
13. Temporary inputs and outputs are cleaned after every run.
14. Terminal status and diagnostics are persisted before broadcast.